// llama.cpp 的 JNI 封装 —— 只做"给定 system+user，吐出纯文本"这一件事。
//
// 用途：字幕翻译。Whisper 只能翻英文（预训练任务固定），
// 中文翻译得靠另一个模型 —— 就像 Transub Player 用 llama.cpp + 混元翻译 7B 那样。
//
// 为什么不在 Java 里手拼 prompt：翻译模型大多是 chat 模型，要它自带的对话模板
// （<|im_start|>… 各种花样）。这边直接用 llama.cpp 的 llama_chat_apply_template
// 套模型内置模板，换模型也不用改代码。

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "MtJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/** 一次加载的模型 + 上下文。 */
struct MtHandle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aznixl_videowall_asr_Mt_nativeInit(JNIEnv *env, jclass, jstring path,
                                            jint nCtx, jint nThreads) {
    if (path == nullptr) return 0;
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    // 安卓上没有 GPU 后端，别指望 offload（ggml 的 Vulkan 在手机上兼容性太差）
    mp.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(cpath, mp);
    if (model == nullptr) {
        LOGE("模型加载失败: %s", cpath);
        env->ReleaseStringUTFChars(path, cpath);
        return 0;
    }
    env->ReleaseStringUTFChars(path, cpath);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 1024u;
    cp.n_batch = 512;
    cp.n_threads = nThreads > 0 ? (int32_t) nThreads : 4;
    cp.n_threads_batch = cp.n_threads;
    // 字幕翻译是短文本，KV cache 不用大；关掉 flash attention 提高兼容性
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        LOGE("上下文创建失败");
        llama_model_free(model);
        return 0;
    }

    auto *h = new MtHandle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    LOGI("翻译模型已加载：%s（ctx=%d, threads=%d）", cpath, (int) cp.n_ctx, (int) cp.n_threads);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_aznixl_videowall_asr_Mt_nativeGenerate(JNIEnv *env, jclass, jlong handle,
                                                jstring system, jstring user, jint maxTokens) {
    auto *h = reinterpret_cast<MtHandle *>(handle);
    if (h == nullptr || h->ctx == nullptr || h->vocab == nullptr) return nullptr;

    const char *sys = env->GetStringUTFChars(system, nullptr);
    const char *usr = env->GetStringUTFChars(user, nullptr);
    if (usr == nullptr) {
        if (sys) env->ReleaseStringUTFChars(system, sys);
        return nullptr;
    }

    // ---- 1. 套模型自带的对话模板
    std::string prompt;
    {
        llama_chat_message msgs[2];
        int n = 0;
        if (sys != nullptr && sys[0] != '\0') {
            msgs[n].role = "system";
            msgs[n].content = sys;
            n++;
        }
        msgs[n].role = "user";
        msgs[n].content = usr;
        n++;

        const char *tmpl = llama_model_chat_template(h->model, nullptr);
        // 先问需要多长，再分配
        int32_t need = llama_chat_apply_template(tmpl, msgs, (size_t) n, true, nullptr, 0);
        if (need > 0) {
            prompt.resize((size_t) need);
            // 注意：apply_template 往 buf 里写的是字节，不保证以 \0 结尾
            int32_t got = llama_chat_apply_template(
                    tmpl, msgs, (size_t) n, true, &prompt[0], need);
            if (got > 0) prompt.resize((size_t) got);
        }
        if (prompt.empty()) {
            // 模型没有内置模板时兜底：直接拼（Hunyuan-MT 之类都认这种）
            if (sys != nullptr && sys[0] != '\0') {
                prompt = std::string(sys) + "\n\n" + usr;
            } else {
                prompt = usr;
            }
        }
    }

    env->ReleaseStringUTFChars(system, sys);
    env->ReleaseStringUTFChars(user, usr);

    // ---- 2. 每轮都清一次 KV cache，避免上一句的上下文串进来
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // ---- 3. tokenize
    const int32_t n_prompt = -llama_tokenize(
            h->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        LOGE("tokenize 失败");
        return nullptr;
    }
    std::vector<llama_token> tokens((size_t) n_prompt);
    if (llama_tokenize(h->vocab, prompt.c_str(), (int32_t) prompt.size(),
                       tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        LOGE("tokenize 第二次失败");
        return nullptr;
    }

    // ---- 4. 喂 prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("decode prompt 失败");
        return nullptr;
    }

    // ---- 5. 贪心解码
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    const int limit = maxTokens > 0 ? maxTokens : 256;
    for (int i = 0; i < limit; i++) {
        // 注意：这里不能用 const —— llama_batch_get_one 收的是非 const 指针
        llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) break;

        char piece[256];
        const int32_t n = llama_token_to_piece(h->vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) out.append(piece, (size_t) n);

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(h->ctx, next) != 0) break;
    }
    llama_sampler_free(smpl);

    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_aznixl_videowall_asr_Mt_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<MtHandle *>(handle);
    if (h == nullptr) return;
    if (h->ctx != nullptr) llama_free(h->ctx);
    if (h->model != nullptr) llama_model_free(h->model);
    delete h;
}

}  // extern "C"
