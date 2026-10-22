package com.aznixl.videowall.asr;

/**
 * 翻译引擎的统一接口。
 *
 * <p>两套实现：
 * <ul>
 *   <li>{@link Translator} —— 在线 OpenAI 兼容接口，质量好、但字幕文本要发出去</li>
 *   <li>{@link LocalMt} —— 本地 llama.cpp + GGUF 翻译模型，完全离线、但慢且吃存储</li>
 * </ul>
 * 上层（{@link AsrWorker} / 批量生成字幕）只认这个接口，换引擎不用改逻辑。
 */
public interface TrEngine {

    /**
     * 翻译一段文本。
     *
     * <p><b>约定：任何失败都返回原文</b>，绝不返回 null 或空串 ——
     * 翻译是为了锦上添花，不能因为翻译挂了连原文都看不到。
     */
    String translate(String text);

    /** 释放资源（本地引擎要放掉模型）。 */
    default void close() {
    }
}
