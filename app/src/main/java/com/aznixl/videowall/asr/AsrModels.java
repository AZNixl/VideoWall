package com.aznixl.videowall.asr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 识别模型（whisper.cpp 的 ggml .bin）的存放与发现。
 *
 * <h3>为什么放在 Android/data/&lt;包名&gt;/files/asr</h3>
 * whisper.cpp 的 C++ 侧是 {@code fopen} 打开的，只认真实路径 —— SAF 给的
 * {@code content://} URI 喂不进去。而安卓 11+ 的分区存储下，应用能"无权限读写的真路径"
 * 只剩自己的 external files 目录。所以这里是唯一同时满足"真路径 + 零权限"的位置。
 *
 * 代价：安卓 11+ 的文件管理器默认进不了 {@code Android/data}。
 * 所以设置页提供「导入模型」—— 用系统文件选择器挑 .bin，应用负责拷进这个目录。
 * 这样用户根本不用关心路径，从哪儿下载的都行。
 */
public final class AsrModels {

    private static final String TAG = "AsrModels";

    /**
     * 模型目录：<b>/sdcard/Android/media/com.aznixl.videowall/asr</b>
     *
     * <h3>为什么是 Android/media 而不是 Android/data</h3>
     * 两个都是"真路径"（whisper.cpp 的 C++ 侧用 {@code fopen} 打开，只认真实路径，
     * SAF 给的 {@code content://} 喂不进去），但：
     * <ul>
     *   <li>{@code Android/data} —— 安卓 11+ 的文件管理器**进不去**，用户没法自己放文件 ✗</li>
     *   <li>{@code Android/media} —— 应用同样有完整路径权限，**同时文件管理器能直接读写** ✓
     *       （WhatsApp / Telegram 那些可见的媒体目录就是这么来的）</li>
     *   <li>{@code Download/} —— 得申请「所有文件访问」权限，为一个模型不值当 ✗</li>
     * </ul>
     * 所以选 Android/media：用户下完模型用任何文件管理器丢进去就行，不用导入。
     * 拿不到时（极少数没有外置存储的环境）退回 files 目录。
     */
    public static File rootDir(Context c) {
        File base = null;
        try {
            File[] media = c.getExternalMediaDirs();
            if (media != null && media.length > 0) base = media[0];
        } catch (Throwable ignored) {
        }
        if (base == null) base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();

        File dir = new File(base, "asr");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "建不出模型目录: " + dir);
        }
        return dir;
    }

    /** 给界面显示用：人类可读的绝对路径。 */
    public static String pathForDisplay(Context c) {
        return rootDir(c).getAbsolutePath();
    }

    // ---------------------------------------------------------------- 翻译模型（GGUF）

    /**
     * 本地翻译模型目录：<b>/sdcard/Android/media/&lt;包名&gt;/mt</b>
     *
     * <p>和识别模型分开放：翻译模型动辄 3–4.6GB，混在一个目录里用户自己也分不清。
     */
    public static File mtDir(Context c) {
        File asrBase = rootDir(c).getParentFile();     // .../media/<pkg>
        File dir = new File(asrBase, "mt");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "建不出翻译模型目录: " + dir);
        }
        return dir;
    }

    public static String mtPathForDisplay(Context c) {
        return mtDir(c).getAbsolutePath();
    }

    /** 扫翻译模型（.gguf）。结构同 {@link #scan}：根目录直接放，或放子目录里。 */
    public static List<Model> scanMt(Context c) {
        List<Model> out = new ArrayList<>();
        File root = mtDir(c);
        File[] files = root.listFiles();
        if (files == null) {
            Log.w(TAG, "翻译模型目录读不了: " + root.getAbsolutePath());
            return out;
        }
        for (File f : files) {
            if (f.isFile() && isMtFile(f)) out.add(new Model(stripExt(f.getName()), f));
        }
        for (File f : files) {
            if (!f.isDirectory()) continue;
            File[] fs = f.listFiles();
            if (fs == null) continue;
            File best = null;
            for (File x : fs) {
                if (x.isFile() && isMtFile(x) && (best == null || x.length() > best.length())) best = x;
            }
            if (best != null) out.add(new Model(f.getName(), best));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static Model findMt(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        for (Model m : scanMt(c)) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    public static Model firstMt(Context c) {
        List<Model> all = scanMt(c);
        return all.isEmpty() ? null : all.get(0);
    }

    private static boolean isMtFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".gguf") && f.length() > 10 * 1024 * 1024;
    }

    /**
     * 推荐的本地翻译模型。体积是按 hf-mirror 上实测的（2026-10 查的）。
     *
     * <p>手机上唯一现实的用法是"批量生成翻译字幕"：7B 在骁龙 8 系上约 5–10 tok/s，
     * 一句字幕 2–5 秒，整片要跑很久；实时那条只能靠领先 60 秒硬扛。
     */
    public static final class RecMt {
        public final String label;
        public final String fileName;
        public final String size;
        public final String note;
        public final String url;

        RecMt(String label, String fileName, String size, String note, String url) {
            this.label = label;
            this.fileName = fileName;
            this.size = size;
            this.note = note;
            this.url = url;
        }
    }

    public static final RecMt[] RECOMMENDED_MT = {
            new RecMt("混元翻译 7B · Q4_K_M（推荐）",
                    "Hunyuan-MT-7B.Q4_K_M.gguf", "4.6 GB",
                    "Transub Player 用的就是这套。质量好，但手机上偏慢",
                    "https://hf-mirror.com/mradermacher/Hunyuan-MT-7B-GGUF/resolve/main/Hunyuan-MT-7B.Q4_K_M.gguf"),
            new RecMt("混元翻译 7B · Q3_K_S",
                    "Hunyuan-MT-7B.Q3_K_S.gguf", "3.4 GB",
                    "体积小一点，质量略降",
                    "https://hf-mirror.com/mradermacher/Hunyuan-MT-7B-GGUF/resolve/main/Hunyuan-MT-7B.Q3_K_S.gguf"),
            new RecMt("混元翻译 7B · Q2_K",
                    "Hunyuan-MT-7B.Q2_K.gguf", "3.0 GB",
                    "最小的 7B 量化，手机存储吃紧时用",
                    "https://hf-mirror.com/mradermacher/Hunyuan-MT-7B-GGUF/resolve/main/Hunyuan-MT-7B.Q2_K.gguf"),
    };

    public static final class Model {
        public final String name;
        public final File file;
        public final long bytes;

        Model(String name, File file) {
            this.name = name;
            this.file = file;
            this.bytes = file.length();
        }

        public String sizeText() {
            return human(bytes);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 扫模型目录。两种摆法都认：
     * <ul>
     *   <li>直接把 {@code ggml-tiny.bin} 丢在 asr/ 下 → 模型名取文件名</li>
     *   <li>放进子目录 {@code asr/tiny/ggml-tiny.bin} → 模型名取目录名（一个模型多个文件时用）</li>
     * </ul>
     */
    public static List<Model> scan(Context c) {
        List<Model> out = new ArrayList<>();
        File root = rootDir(c);
        File[] files = root.listFiles();
        if (files == null) {
            // 踩过一次：目录是别人（adb shell）建的、属主不对，应用看得见路径却读不了，
            // listFiles() 直接给 null。不报错的话上层会安静地判成"没有模型"。
            Log.w(TAG, "模型目录读不了（属主/权限问题？）: " + root.getAbsolutePath());
            return out;
        }

        for (File f : files) {
            if (f.isFile() && isModelFile(f)) {
                out.add(new Model(stripExt(f.getName()), f));
            }
        }
        for (File f : files) {
            if (!f.isDirectory()) continue;
            File bin = biggestBin(f);
            if (bin != null) out.add(new Model(f.getName(), bin));
        }
        // 大的排前面？不 —— 按名字排，稳定的顺序更好找
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static Model find(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        for (Model m : scan(c)) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    /** 兜底：没有配置时用第一个找到的（只有一个模型的常见情况）。 */
    public static Model first(Context c) {
        List<Model> all = scan(c);
        return all.isEmpty() ? null : all.get(0);
    }

    private static boolean isModelFile(File f) {
        String n = f.getName().toLowerCase();
        // whisper.cpp 的模型都是 ggml-*.bin；有的打包成 .gguf，一并认了
        return (n.endsWith(".bin") || n.endsWith(".gguf")) && f.length() > 1024 * 1024;
    }

    private static File biggestBin(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) {
            if (f.isFile() && isModelFile(f) && (best == null || f.length() > best.length())) {
                best = f;
            }
        }
        return best;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String human(long bytes) {
        if (bytes <= 0) return "0";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }

    // ---------------------------------------------------------------- 语言与显示名

    /** Whisper 语言码。设置页和播放页的字幕弹窗共用这一份，避免两处定义漂移。 */
    public static final String[] LANGS = {"auto", "zh", "en", "ja", "ko", "fr"};
    public static final String[] LANG_NAMES = {"自动", "中文", "英语", "日语", "韩语", "法语"};

    /** 模型名在界面上显示时去掉 ggml- 前缀（"ggml-tiny-q5_1" → "tiny-q5_1"）。 */
    public static String shortName(String name) {
        if (name == null) return "";
        return name.startsWith("ggml-") ? name.substring(5) : name;
    }

    /** 语言码 → 在 {@link #LANGS} 里的下标。 */
    public static int langIndex(String code) {
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(code)) return i;
        }
        return 0;
    }

    // ---------------------------------------------------------------- 推荐模型

    /**
     * 推荐清单。显示在设置页里，带 hf-mirror 的直链 —— 国内直连 huggingface 基本不通，
     * 但 hf-mirror 可以。
     *
     * 手机端的现实取舍：tiny/base 是能跟得上播放的档位；small 以上基本只能当"离线整片转写"用。
     */
    public static final class Rec {
        public final String label;
        public final String fileName;
        public final String size;
        public final String note;
        public final String url;

        Rec(String label, String fileName, String size, String note) {
            this.label = label;
            this.fileName = fileName;
            this.size = size;
            this.note = note;
            this.url = "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/" + fileName;
        }
    }

    public static final Rec[] RECOMMENDED = {
            new Rec("Tiny · q5_1（推荐）", "ggml-tiny-q5_1.bin", "32 MB",
                    "多语言，速度最快。手机上大约能跑到 3–8 倍实时，适合边看边出字幕"),
            new Rec("Tiny · q8_0", "ggml-tiny-q8_0.bin", "44 MB", "比 q5_1 略准、略慢"),
            new Rec("Base · q5_1", "ggml-base-q5_1.bin", "60 MB",
                    "准一些，速度约 Tiny 的一半。中端机建议先用 Tiny 试"),
            new Rec("Base · q8_0", "ggml-base-q8_0.bin", "82 MB", "准确性优先时的折中"),
            new Rec("Small · q5_1", "ggml-small-q5_1.bin", "190 MB",
                    "明显更准，但手机 CPU 上通常跟不上实时，只适合整片离线转写"),
            new Rec("Large v3 Turbo · q5_0", "ggml-large-v3-turbo-q5_0.bin", "574 MB",
                    "质量最高。手机上只当作离线批处理用，别指望实时"),
    };
}
