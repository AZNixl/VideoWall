package com.aznixl.videowall.asr;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 实时字幕的后台引擎。
 *
 * <h3>核心思路：让识别跑在播放头前面</h3>
 * 通常的"实时字幕"是麦克风那套 —— 边录边识别，字幕必然滞后几秒。
 * 但这里音频来自**文件**，可以随便往前读。所以策略反过来：
 * <b>始终比播放头多转录一段（默认 60 秒），提前把字幕准备好</b>。
 * 你看到某句话的时候，字幕早就躺在列表里了 —— 除了刚 seek 完那一两秒，
 * 基本感觉不到延迟。这比"边看边等"的做法体验好得多。
 *
 * <h3>分块为什么找静音点</h3>
 * Whisper 是非流式的，只能一块一块吃（这里 8 秒一块）。硬切在固定边界上
 * 会把词切成两半，出现"我今天去" / "了超市"这种断句。所以在目标边界前 2.5 秒内
 * 找能量最低的 100ms 窗口当切点，切口落在换气处，断句自然得多。
 */
public final class AsrWorker {

    private static final String TAG = "AsrWorker";

    /** 每块的目标长度（毫秒）。8 秒是"延迟 / 上下文足够"的折中。 */
    private static final int CHUNK_MS = 8000;
    /** 转录最多领先播放头多少毫秒。太少会卡字幕，太多纯烧电。 */
    private static final int LOOKAHEAD_MS = 60_000;
    /** 找静音点的回溯范围。 */
    private static final int SILENCE_WINDOW_MS = 2500;

    public static final class Cue {
        public final long startMs;
        public final long endMs;
        public final String text;

        Cue(long s, long e, String t) {
            startMs = s;
            endMs = e;
            text = t;
        }
    }

    public interface Listener {
        /** 新增字幕。可能在任意线程被调用。 */
        void onCues(List<Cue> cues);

        /** 状态变化（"正在加载模型"、"识别中"…）。 */
        void onStatus(String status);

        void onError(String message);
    }

    private final Context appCtx;
    private final Uri uri;
    private final File modelFile;
    private final String language;
    private final boolean translate;
    private final int threads;
    private final Listener listener;

    private volatile boolean stopped;
    private volatile long playheadMs;
    private volatile long restartToMs = -1;
    private Thread thread;

    public AsrWorker(Context ctx, Uri uri, File modelFile, String language, boolean translate,
                     int threads, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.uri = uri;
        this.modelFile = modelFile;
        this.language = language == null ? "auto" : language;
        this.translate = translate;
        this.threads = Math.max(1, threads);
        this.listener = listener;
    }

    public void start(long fromMs) {
        restartToMs = fromMs;
        playheadMs = fromMs;
        thread = new Thread(this::loop, "asr-worker");
        thread.setDaemon(true);
        thread.start();
    }

    /** 由播放器的 ticker 调，用来节流和发现 seek。 */
    public void onPlayhead(long posMs) {
        playheadMs = posMs;
    }

    public void stop() {
        stopped = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    // ------------------------------------------------------------------ 主循环

    private void loop() {
        AudioPump pump = null;
        long ctx = 0;
        try {
            listener.onStatus("正在加载模型…");
            ctx = ModelCache.acquire(modelFile);
            if (ctx == 0) {
                listener.onError("模型加载失败：" + modelFile.getName());
                return;
            }
            listener.onStatus("准备音频…");
            pump = new AudioPump(appCtx, uri);

            long startAt = restartToMs >= 0 ? restartToMs : 0;
            pump.seekTo(startAt);

            float[] buf = new float[CHUNK_MS * 16 + AudioPump.TARGET_RATE * 2];
            int len = 0;                       // buf 里的样本数
            long rangeStart = startAt;         // 当前这条管线覆盖的起点
            long chunkStartMs = startAt;       // 当前这批样本的起始时间
            long decodedMs = startAt;          // 已经解出来（未识别）的末尾时间
            String lastText = "";
            float[] tmp = new float[16000];

            restartToMs = -1;

            while (!stopped) {
                // ---- seek 处理：播放头跳到别处，整条管线重来
                if (restartToMs >= 0) {
                    long to = restartToMs;
                    restartToMs = -1;
                    pump.seekTo(to);
                    len = 0;
                    rangeStart = to;
                    chunkStartMs = to;
                    decodedMs = to;
                    lastText = "";
                    listener.onStatus("已跳转，重新识别…");
                    Log.i(TAG, "seek 到 " + to);
                    continue;
                }
                // ---- 播放头跑出已处理区间 → 重来
                //   往回拖到起点之前、或往前拖到已解码范围之外，这两种情况下
                //   手上的数据都没用了。之所以要看两个方向：只判"往回"的话，
                //   往前拖会把下面的节流判断卡死（永远"领先太多"、一直睡）。
                if (playheadMs < rangeStart - 1000 || playheadMs > decodedMs + 2000) {
                    restartToMs = Math.max(0, playheadMs);
                    continue;
                }

                // ---- 节流：领先太多就歇着（别把整部片子一口气烧完电）
                if (decodedMs - playheadMs > LOOKAHEAD_MS) {
                    sleep(200);
                    continue;
                }

                int n = pump.read(tmp, tmp.length);
                if (n > 0) {
                    ensure(buf, len, n);
                    System.arraycopy(tmp, 0, buf, len, n);
                    len += n;
                    decodedMs += n / 16;      // 16 kHz → 每样本 1/16 ms
                }

                boolean eof = n < 0;
                int pendingMs = len / 16;
                boolean ready = pendingMs >= CHUNK_MS || (eof && pendingMs >= 400);
                if (!ready) {
                    if (eof) break;
                    continue;
                }

                // 切点：非 EOF 时找静音处；EOF 就全吃掉
                int cutSamples = eof ? len : findSilenceCut(buf, len);
                if (cutSamples < AudioPump.TARGET_RATE / 2) cutSamples = len;   // 太短就整块吃
                cutSamples = Math.min(cutSamples, len);

                float[] chunk = new float[cutSamples];
                System.arraycopy(buf, 0, chunk, 0, cutSamples);
                int rest = len - cutSamples;
                if (rest > 0) System.arraycopy(buf, cutSamples, buf, 0, rest);
                len = rest;

                long thisStart = chunkStartMs;
                chunkStartMs += cutSamples / 16;

                String[] segs;
                long tInfer0 = System.currentTimeMillis();
                try {
                    segs = Whisper.nativeFull(ctx, chunk, chunk.length, language, translate, threads);
                } catch (Throwable t) {
                    Log.w(TAG, "识别抛异常", t);
                    continue;
                }
                long inferMs = System.currentTimeMillis() - tInfer0;
                Log.i(TAG, "本块 " + (chunk.length / 16) + "ms 音频，识别耗时 " + inferMs
                        + "ms，段落 " + (segs == null ? 0 : segs.length));
                if (segs == null) continue;

                List<Cue> out = new ArrayList<>();
                for (String s : segs) {
                    String text = Whisper.parseText(s);
                    long[] t = Whisper.parseTimes(s);
                    if (t == null || text.isEmpty()) continue;
                    if (!isUseful(text)) continue;
                    if (text.equals(lastText)) continue;      // whisper 偶尔原地复读
                    lastText = text;
                    out.add(new Cue(thisStart + t[0], thisStart + t[1], text));
                }
                if (!out.isEmpty()) listener.onCues(out);

                listener.onStatus("已识别到 " + fmtMs(chunkStartMs));

                if (eof) break;
            }
            listener.onStatus("识别完成");
        } catch (Throwable t) {
            Log.w(TAG, "识别线程异常退出", t);
            listener.onError(String.valueOf(t.getMessage()));
        } finally {
            if (pump != null) pump.close();
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 在 [len - 2.5s, len] 里找能量最低的 100ms 窗口，返回窗口中心作为切点。
     * 找不到（整段都很响）就返回 len —— 宁可硬切也不拖长。
     */
    private static int findSilenceCut(float[] buf, int len) {
        int win = 100 * 16;            // 100ms @16k
        int from = Math.max(0, len - SILENCE_WINDOW_MS * 16);
        if (len - from < win * 2) return len;

        int best = len;
        double bestEnergy = Double.MAX_VALUE;
        // 每 20ms 挪一次窗口
        for (int i = from; i + win <= len; i += 20 * 16) {
            double e = 0;
            // 窗口内每 4 个样本取一个，够判断了，省点 CPU
            for (int j = i; j < i + win; j += 4) e += Math.abs(buf[j]);
            if (e < bestEnergy) {
                bestEnergy = e;
                best = i + win / 2;
            }
        }
        // 要求切点之后至少还剩 0.5s，否则没有意义
        return (len - best) >= AudioPump.TARGET_RATE / 2 ? best : len;
    }

    /** 过滤 whisper 在静音上的幻觉和纯符号。 */
    private static boolean isUseful(String text) {
        String s = text.trim();
        if (s.isEmpty()) return false;
        // 整段都被括号包着 —— 典型的 [BLANK_AUDIO] / (silence) / 【音乐】
        char c0 = s.charAt(0);
        char c1 = s.charAt(s.length() - 1);
        if ((c0 == '[' && c1 == ']') || (c0 == '(' && c1 == ')')
                || (c0 == '【' && c1 == '】') || (c0 == '（' && c1 == '）')) {
            return false;
        }
        // 没有任何字母/汉字/数字 → 纯标点
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) return true;
        }
        return false;
    }

    private static void ensure(float[] buf, int len, int add) {
        if (len + add <= buf.length) return;
        throw new IllegalStateException("缓冲区溢出（不该发生）");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fmtMs(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * 模型上下文缓存。
     *
     * 加载一次的成本很实在（Tiny 约 30MB 权重 + 运行时内存，Large 要几百 MB），
     * 换视频时如果重新加载，每次都要等好几秒。所以只留一个槽位，同一个模型文件就复用。
     */
    private static final class ModelCache {
        private static String cachedPath;
        private static long cachedCtx;

        static synchronized long acquire(File model) {
            String path = model.getAbsolutePath();
            if (cachedCtx != 0 && path.equals(cachedPath)) {
                Log.i(TAG, "复用已加载的模型");
                return cachedCtx;
            }
            if (cachedCtx != 0) {
                Whisper.nativeFree(cachedCtx);
                cachedCtx = 0;
                cachedPath = null;
            }
            long ctx = Whisper.tryInit(path);
            if (ctx != 0) {
                cachedCtx = ctx;
                cachedPath = path;
            }
            return ctx;
        }

        static synchronized void freeAll() {
            if (cachedCtx != 0) {
                Whisper.nativeFree(cachedCtx);
                cachedCtx = 0;
                cachedPath = null;
            }
        }
    }

    /** 进程退出或用户换模型时调。 */
    public static void releaseAll() {
        ModelCache.freeAll();
    }
}
