package com.aznixl.videowall.asr;

import android.util.Log;

import java.io.File;

/**
 * 本地翻译引擎：llama.cpp + GGUF 翻译模型（比如腾讯混元翻译 Hunyuan-MT-7B 的量化版）。
 *
 * <h3>为什么要单独一个引擎</h3>
 * Whisper 自带的 translate 只能出英文（预训练任务固定成"任意语言→英文"）。
 * 想要中文、又不想联网，就得像 Transub Player 那样再挂一个翻译模型 ——
 * 它用的是 llama.cpp + 混元翻译 7B，这套在桌面跑得很好。
 *
 * <h3>手机上的现实</h3>
 * 7B 的 Q4_K_M 是 4.6GB，在骁龙 8 系上大概 5–10 tok/s —— **一句字幕 2–5 秒**。
 * 所以：
 * <ul>
 *   <li><b>批量生成翻译字幕</b>：合适。整片慢慢翻，翻完存成 .srt 下次直接用 ✓</li>
 *   <li><b>实时翻译</b>：勉强（靠"识别领先播放头 60 秒"硬扛），所以做成了开关，默认关 ✓</li>
 * </ul>
 *
 * <p>模型只加载一次，复用句柄；换模型时由调用方 {@link #close()}。
 */
public final class LocalMt implements TrEngine {

    private static final String TAG = "LocalMt";

    /** 字幕翻译的指令。混元翻译这类模型对祈使句式的指令响应最稳。 */
    private static final String SYSTEM =
            "你是专业的字幕翻译。把用户给的台词翻译成简体中文。"
                    + "只输出译文本身：不要解释、不要加引号、不要重复原文、不要加注音。"
                    + "台词要保持口语，短句就译成短句。";

    private long handle;
    private final String targetLang;
    private final int maxTokens;

    /**
     * @param model     GGUF 模型文件
     * @param threads   推理线程数（4 是手机上的稳妥值）
     * @param targetLang 目标语言，目前就是"简体中文"
     */
    public LocalMt(File model, int threads, String targetLang) {
        this.targetLang = targetLang == null || targetLang.isEmpty() ? "简体中文" : targetLang;
        // 一句字幕最多几十 token，128 足够；给多了只会让模型自由发挥
        this.maxTokens = 160;
        // KV cache 1024 够放"系统提示 + 一句台词"
        this.handle = Mt.tryInit(model, 1024, Math.max(1, threads));
        if (handle == 0) Log.e(TAG, "本地翻译引擎不可用：" + model.getName());
    }

    public boolean isReady() {
        return handle != 0;
    }

    @Override
    public synchronized String translate(String text) {
        if (handle == 0) return text;
        if (text == null || text.trim().isEmpty()) return text;

        String sys = targetLang.equals("简体中文") ? SYSTEM
                : SYSTEM.replace("简体中文", targetLang);
        try {
            long t0 = System.currentTimeMillis();
            String out = Mt.nativeGenerate(handle, sys, text, maxTokens);
            long cost = System.currentTimeMillis() - t0;
            if (out == null) return text;
            out = cleanup(out);
            Log.i(TAG, "本地翻译 " + text.length() + " 字 → " + out.length()
                    + " 字，耗时 " + cost + "ms");
            return out.isEmpty() ? text : out;
        } catch (Throwable t) {
            Log.w(TAG, "本地翻译失败，回落原文", t);
            return text;
        }
    }

    /** 模型偶尔会带引号、换行或把指令复读一遍，这里收拾一下。 */
    private static String cleanup(String s) {
        String out = s.trim();
        // 取第一段非空内容（有的模型会先输出"译文："再跟正文）
        int nl = out.indexOf('\n');
        if (nl > 0) out = out.substring(0, nl).trim();
        if (out.length() > 1 && (out.startsWith("\"") && out.endsWith("\""))) {
            out = out.substring(1, out.length() - 1).trim();
        }
        if (out.startsWith("译文：")) out = out.substring(3).trim();
        if (out.startsWith("译文:")) out = out.substring(3).trim();
        return out;
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            try {
                Mt.nativeFree(handle);
            } catch (Throwable ignored) {
            }
            handle = 0;
        }
    }
}
