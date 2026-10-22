// VideoWall 的 whisper.cpp JNI 封装。
//
// 只暴露三个方法，够用就好：
//   nativeInit(modelPath)           → 加载 ggml 模型，返回 ctx 指针（0 表示失败）
//   nativeFull(ctx, pcm16k, n, lang, translate) → String[]，每项 "t0\tt1\ttext"（毫秒）
//   nativeFree(ctx)
//
// 为什么不直接用现成的 JNI 绑定：whisper-jni（io.github.givimad）只带 debian/macos/win 的
// 动态库，是 glibc 编的，安卓的 bionic 加载不了；sherpa-onnx 能吃 Whisper，但它传递依赖
// libonnxruntime.so，每个 ABI 15MB 以上，APK 会从 5MB 涨到 85MB。自己编这几个文件，
// 出来的 .so 一共几 MB，而且吃的是 whisper.cpp 原生的 ggml 模型。

#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdint>
#include <string>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aznixl_videowall_asr_Whisper_nativeInit(JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) return 0;
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;

    whisper_context_params cp = whisper_context_default_params();
    // 不用 GPU：安卓上 ggml 的 Vulkan/OpenCL 后端支持面太窄，先只走 CPU。
    cp.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(cpath, cp);
    if (ctx == nullptr) {
        LOGE("加载模型失败: %s", cpath);
    } else {
        LOGI("模型已加载: %s", cpath);
    }
    env->ReleaseStringUTFChars(path, cpath);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jobjectArray JNICALL
Java_com_aznixl_videowall_asr_Whisper_nativeFull(JNIEnv *env, jclass, jlong ctxPtr,
                                                 jfloatArray samples, jint n,
                                                 jstring language, jboolean translate,
                                                 jint nThreads) {
    whisper_context *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr || samples == nullptr || n <= 0) return nullptr;

    jfloat *buf = env->GetFloatArrayElements(samples, nullptr);
    if (buf == nullptr) return nullptr;

    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = nThreads > 0 ? nThreads : 4;
    p.translate        = translate == JNI_TRUE;
    p.language         = (lang != nullptr && lang[0] != '\0') ? lang : "auto";
    // 每块音频独立识别，不带上一块的上下文 —— 分块流式的话，
    // 拿上一块的文本当 prompt 会让错误一直传染下去。
    p.no_context       = true;
    p.single_segment   = false;
    p.print_special    = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_timestamps = false;
    p.suppress_blank   = true;

    const int rc = whisper_full(ctx, p, buf, static_cast<int>(n));

    env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);

    if (rc != 0) {
        LOGE("whisper_full 失败, rc=%d", rc);
        return nullptr;
    }

    const int nseg = whisper_full_n_segments(ctx);
    jclass strCls = env->FindClass("java/lang/String");
    if (strCls == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(nseg, strCls, nullptr);
    if (out == nullptr) return nullptr;

    for (int i = 0; i < nseg; i++) {
        const char *txt = whisper_full_get_segment_text(ctx, i);
        // whisper 的时间戳单位是 10ms
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        std::string line = std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + (txt ? txt : "");
        jstring js = env->NewStringUTF(line.c_str());
        if (js != nullptr) {
            env->SetObjectArrayElement(out, i, js);
            env->DeleteLocalRef(js);
        }
    }
    return out;
}

JNIEXPORT void JNICALL
Java_com_aznixl_videowall_asr_Whisper_nativeFree(JNIEnv *, jclass, jlong ctxPtr) {
    whisper_context *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

}  // extern "C"
