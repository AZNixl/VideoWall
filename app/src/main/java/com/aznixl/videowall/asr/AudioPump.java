package com.aznixl.videowall.asr;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 把视频里**只有音频轨**解出来，变成 Whisper 要的 16 kHz 单声道 float PCM。
 *
 * <p>几个要点：
 * <ul>
 *   <li>只 {@code selectTrack} 音频轨 —— 不解视频，所以四路 4K 的片子同时转写也不吃力
 *       （音频解码的开销相对视频可以忽略）。</li>
 *   <li>解码器输出的采样率/声道数由**输出格式**决定（不是容器里写的那个），
 *       所以重采样必须在 {@code INFO_OUTPUT_FORMAT_CHANGED} 之后按实际值做。</li>
 *   <li>PCM 可能是 16bit 也可能是 float —— 两种都处理。</li>
 * </ul>
 *
 * <p>这个类是"推"式：调用方反复 {@link #read} 要数据，内部自己跑 extractor/codec 的循环。
 */
public final class AudioPump implements Closeable {

    private static final String TAG = "AsrAudioPump";
    /** Whisper 固定 16 kHz。 */
    public static final int TARGET_RATE = 16000;

    private final MediaExtractor extractor = new MediaExtractor();
    private MediaCodec codec;
    private ByteBuffer codecOut;

    private int srcRate = TARGET_RATE;
    private int srcChannels = 1;
    private boolean pcmFloat = false;
    private boolean inputDone = false;
    private boolean outputDone = false;
    private boolean formatKnown = false;

    /** 解码后的单声道样本（源采样率）。 */
    private float[] mono = new float[TARGET_RATE];
    private int monoLen = 0;
    /** 重采样相位：当前输出点在 mono[] 中的位置。 */
    private double phase = 0;

    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private long samplesOut = 0;

    public AudioPump(Context ctx, Uri uri) throws IOException {
        extractor.setDataSource(ctx, uri, null);

        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                fmt = f;
                break;
            }
        }
        if (track < 0 || fmt == null) {
            throw new IOException("这个文件没有音频轨");
        }
        extractor.selectTrack(track);

        String mime = fmt.getString(MediaFormat.KEY_MIME);
        if (mime == null) throw new IOException("音频轨没有 MIME");
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();
        Log.i(TAG, "音频轨 " + mime + " 已就绪");
    }

    /** 已经产出的 16k 样本数 —— 用来算时间轴（除以 16000 就是秒）。 */
    public long producedSamples() {
        return samplesOut;
    }

    /**
     * 从指定毫秒重新开始。会 flush 掉解码器里积压的数据。
     * 注意：只能落在关键帧上，实际起点可能比要求的时间略早（音频帧很小，误差可忽略）。
     */
    public void seekTo(long ms) {
        try {
            extractor.seekTo(ms * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            // flush() 之后编解码器进入 Flushed 子状态，可以直接重新喂输入 ——
            // 这里**不能再调 start()**，否则抛
            // "start() is valid only at Configured state; currently at Running state"。
            codec.flush();
            inputDone = false;
            outputDone = false;
            monoLen = 0;
            phase = 0;
            samplesOut = 0;
        } catch (Throwable t) {
            Log.w(TAG, "seek 失败", t);
        }
    }

    /**
     * 读最多 {@code maxSamples} 个 16k 样本。
     *
     * @return 实际写入的样本数；-1 表示音频已到结尾
     */
    public int read(float[] out, int maxSamples) {
        int written = 0;
        int spins = 0;
        while (written < maxSamples) {
            // 先把重采样器里能榨的输出榨干
            int n = resample(out, written, maxSamples - written);
            written += n;
            if (written >= maxSamples) break;
            if (outputDone && n == 0) {
                // 还有没有残留的尾巴？没有就结束
                if (!resampleHasTail()) {
                    samplesOut += written;
                    return written > 0 ? written : -1;
                }
            }
            if (!feedDecoder()) {
                samplesOut += written;
                return written > 0 ? written : -1;
            }
            if (++spins % 200 == 0) {
                // 每 200 轮还没凑够就打一条：正常情况不该出现，
                // 出现了就说明解码器在空转（没数据 / 卡住）
                Log.w(TAG, "空转 spins=" + spins + " written=" + written
                        + " fmt=" + formatKnown + " inDone=" + inputDone
                        + " outDone=" + outputDone + " mono=" + monoLen);
            }
        }
        samplesOut += written;
        if (samplesOut % (TARGET_RATE * 5) < written) {
            Log.i(TAG, "已产出 " + (samplesOut / TARGET_RATE) + " 秒音频"
                    + "（" + srcRate + "Hz→16k, " + srcChannels + "ch）");
        }
        return written;
    }

    private boolean resampleHasTail() {
        return formatKnown && phase + 1 < monoLen;
    }

    /** 把 mono[] 重采样写入 out，返回写入数量。 */
    private int resample(float[] out, int offset, int max) {
        if (!formatKnown || monoLen < 2) return 0;
        double step = (double) srcRate / TARGET_RATE;
        int n = 0;
        while (n < max) {
            int i = (int) phase;
            if (i + 1 >= monoLen) break;
            float frac = (float) (phase - i);
            out[offset + n] = mono[i] + (mono[i + 1] - mono[i]) * frac;
            phase += step;
            n++;
        }
        // 丢掉已经用掉的样本，保留相位所在点之后的部分
        int drop = Math.min((int) phase, monoLen);
        if (drop > 0) {
            System.arraycopy(mono, drop, mono, 0, monoLen - drop);
            monoLen -= drop;
            phase -= drop;
        }
        return n;
    }

    /**
     * 跑一轮 codec 循环，把解出来的 PCM 追加到 mono[]。
     *
     * @return false 表示再也不会产生新样本了
     */
    private boolean feedDecoder() {
        if (outputDone) return false;

        // 送输入
        if (!inputDone) {
            int inIdx = codec.dequeueInputBuffer(5000);
            if (inIdx >= 0) {
                ByteBuffer in = codec.getInputBuffer(inIdx);
                if (in == null) return true;
                int size = extractor.readSampleData(in, 0);
                if (size < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                    extractor.advance();
                }
            }
        }

        // 收输出
        int outIdx = codec.dequeueOutputBuffer(info, 5000);
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat f = codec.getOutputFormat();
            srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            srcChannels = Math.max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            pcmFloat = f.getInteger(MediaFormat.KEY_PCM_ENCODING,
                    android.media.AudioFormat.ENCODING_PCM_16BIT)
                    == android.media.AudioFormat.ENCODING_PCM_FLOAT;
            formatKnown = true;
            Log.i(TAG, "解码格式: " + srcRate + " Hz / " + srcChannels + " ch / "
                    + (pcmFloat ? "float" : "int16"));
            return true;
        }
        if (outIdx < 0) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            return !outputDone || resampleHasTail();
        }

        codecOut = codec.getOutputBuffer(outIdx);
        if (codecOut != null && info.size > 0) {
            appendPcm(codecOut, info.offset, info.size);
        }
        codec.releaseOutputBuffer(outIdx, false);

        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && inputDone) {
            outputDone = true;
        }
        return true;
    }

    /** 按 PCM 格式解出 float，混成单声道，追加到 mono[]。 */
    private void appendPcm(ByteBuffer buf, int offset, int size) {
        buf.order(ByteOrder.nativeOrder());
        buf.position(offset);
        buf.limit(offset + size);

        int bytesPerSample = pcmFloat ? 4 : 2;
        int frames = size / (bytesPerSample * srcChannels);
        ensureCapacity(monoLen + frames);

        float scale = (1f / 32768f);
        for (int i = 0; i < frames; i++) {
            float acc = 0;
            for (int c = 0; c < srcChannels; c++) {
                if (pcmFloat) {
                    acc += buf.getFloat();
                } else {
                    acc += buf.getShort() * scale;
                }
            }
            mono[monoLen++] = acc / srcChannels;
        }
    }

    private void ensureCapacity(int need) {
        if (need <= mono.length) return;
        int cap = mono.length;
        while (cap < need) cap *= 2;
        float[] bigger = new float[cap];
        System.arraycopy(mono, 0, bigger, 0, monoLen);
        mono = bigger;
    }

    @Override
    public void close() {
        try {
            codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            codec.release();
        } catch (Throwable ignored) {
        }
        try {
            extractor.release();
        } catch (Throwable ignored) {
        }
        codec = null;
    }
}
