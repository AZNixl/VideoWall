package com.aznixl.videowall.asr;

import android.util.Log;

/**
 * whisper.cpp 的 JNI 入口。
 *
 * 这里**故意只做最薄的一层** —— 所有业务逻辑（分块、节流、时间轴对齐）都在 Java 里，
 * 方便改动而不用重新编 .so。C++ 那侧只看三件事：加载模型、跑一段 PCM、释放。
 *
 * <p>注意：{@link #full} 返回的每一段是 {@code "t0\tt1\t文本"}（毫秒），
 * 时间戳是**相对传入音频起点**的，调用方要自己加上这一块的起始时间。
 */
public final class Whisper {

    private static final String TAG = "WhisperJni";

    static {
        System.loadLibrary("whisper_jni");
    }

    private Whisper() {
    }

    /**
     * 加载 ggml 模型（.bin）。失败返回 0。
     *
     * <p><b>方法名不能随便改</b>：JNI 的符号名是按 Java 方法名拼出来的
     * （{@code Java_com_aznixl_videowall_asr_Whisper_nativeInit}），
     * 和 C++ 侧的 {@code Java_..._nativeInit} 必须逐字对上。
     * 改成 {@code init()} 之类编译照样过，但一跑就 {@code UnsatisfiedLinkError}。
     */
    public static native long nativeInit(String modelPath);

    /**
     * 识别一段 16kHz 单声道 float PCM。
     *
     * @param ctx        {@link #nativeInit} 的返回值
     * @param samples    [-1,1] 的采样
     * @param language   Whisper 语言码（"zh" / "en" / "ja"…），传 "" 或 "auto" 自动判断
     * @param translate  true = 翻译成英文（Whisper 原生能力），false = 转写
     * @param threads    推理线程数
     * @return 每段一条 "t0\tt1\t文本"；识别失败返回 null
     */
    public static native String[] nativeFull(long ctx, float[] samples, int n,
                                             String language, boolean translate, int threads);

    public static native void nativeFree(long ctx);

    /** 把 "t0\tt1\ttext" 拆开。返回 null 表示这行格式不对（直接丢掉）。 */
    public static long[] parseTimes(String line) {
        if (line == null) return null;
        int a = line.indexOf('\t');
        if (a <= 0) return null;
        int b = line.indexOf('\t', a + 1);
        if (b <= a) return null;
        try {
            long t0 = Long.parseLong(line.substring(0, a));
            long t1 = Long.parseLong(line.substring(a + 1, b));
            return t1 > t0 ? new long[]{t0, t1} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取 "t0\tt1\ttext" 里的文本部分。 */
    public static String parseText(String line) {
        if (line == null) return "";
        int a = line.indexOf('\t');
        if (a <= 0) return "";
        int b = line.indexOf('\t', a + 1);
        if (b <= a) return "";
        return line.substring(b + 1).trim();
    }

    /** 模型加载一次的成本不低（几百 MB 内存），所以调用方要缓存 ctx。 */
    public static long tryInit(String modelPath) {
        try {
            long ctx = nativeInit(modelPath);
            if (ctx == 0) Log.e(TAG, "模型加载失败：" + modelPath);
            return ctx;
        } catch (Throwable t) {
            Log.e(TAG, "模型加载抛异常：" + modelPath, t);
            return 0;
        }
    }
}
