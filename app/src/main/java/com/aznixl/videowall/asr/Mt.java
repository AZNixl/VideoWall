package com.aznixl.videowall.asr;

import android.util.Log;

import java.io.File;

/**
 * llama.cpp 的 JNI 入口（字幕翻译用）。
 *
 * <p>方法名不能改：JNI 符号按 Java 方法名拼（{@code Java_com_aznixl_videowall_asr_Mt_nativeInit}），
 * 和 C++ 侧必须逐字对上。改名编译照样过，一跑就 {@code UnsatisfiedLinkError}。
 */
public final class Mt {

    private static final String TAG = "MtJni";

    static {
        System.loadLibrary("llama_jni");
    }

    private Mt() {
    }

    /**
     * 加载 GGUF 模型并建上下文。
     *
     * @param modelPath gguf 绝对路径
     * @param nCtx      KV cache 长度。字幕是短文本，1024 够用；调大很吃内存
     * @param nThreads  推理线程数
     * @return 句柄；0 表示失败
     */
    public static native long nativeInit(String modelPath, int nCtx, int nThreads);

    /**
     * 跑一次生成。system/user 会按模型自带的对话模板拼成 prompt。
     *
     * @return 生成的文本（已去掉特殊 token）；失败返回 null
     */
    public static native String nativeGenerate(long handle, String system, String user, int maxTokens);

    public static native void nativeFree(long handle);

    /** 带异常防护的加载。 */
    public static long tryInit(File model, int nCtx, int threads) {
        try {
            long h = nativeInit(model.getAbsolutePath(), nCtx, threads);
            if (h == 0) Log.e(TAG, "翻译模型加载失败：" + model.getAbsolutePath());
            return h;
        } catch (Throwable t) {
            Log.e(TAG, "翻译模型加载异常", t);
            return 0;
        }
    }
}
