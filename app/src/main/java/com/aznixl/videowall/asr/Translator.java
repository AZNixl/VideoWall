package com.aznixl.videowall.asr;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 字幕翻译：走 OpenAI 兼容的 chat/completions 接口。
 *
 * <h3>为什么是在线 API</h3>
 * 想要**中文**字幕，端侧路线都很重：
 * <ul>
 *   <li>Whisper 自带的 translate 只能出英文（预训练目标固定）✗</li>
 *   <li>Opus-MT / NLLB 这类 MT 模型要 ONNX Runtime（每 ABI 15MB+），
 *       而且都依赖 SentencePiece 分词器 —— 纯 Java 实现代价太大 ✗</li>
 *   <li>ML Kit 翻译依赖 Google 服务，国内基本不可用 ✗</li>
 * </ul>
 * 所以留一条"用户自己填端点"的路：任何 OpenAI 兼容的接口都能用，
 * 包括国内的 DeepSeek / 通义 / 智谱等。**代价是字幕文本会发到该服务**，
 * 所以默认关闭，且设置里写明。
 *
 * <p>设计原则：**翻译永远不能拖累字幕**。任何失败都返回原文，
 * 不让用户因为网络问题连字幕都看不到。
 */
public final class Translator implements TrEngine {

    private static final String TAG = "AsrTranslator";

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String targetLang;

    public Translator(String baseUrl, String apiKey, String model, String targetLang) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        // 允许用户只填到 /v1，也允许直接填完整的 completions 路径
        if (!base.isEmpty() && !base.endsWith("/chat/completions")) {
            base = base + "/chat/completions";
        }
        this.endpoint = base;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.trim().isEmpty() ? "gpt-4o-mini" : model.trim();
        this.targetLang = targetLang == null || targetLang.isEmpty() ? "简体中文" : targetLang;
    }

    public boolean isConfigured() {
        return !endpoint.isEmpty() && !apiKey.isEmpty();
    }

    /**
     * 把一段文本翻成目标语言。
     *
     * @return 译文；任何失败都返回**原文**（宁可显示原文，也不要空字幕）
     */
    @Override
    public String translate(String text) {
        if (text == null || text.trim().isEmpty()) return text;
        if (!isConfigured()) return text;

        HttpURLConnection conn = null;
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content",
                    "你是字幕翻译。把用户给的文本翻译成" + targetLang + "。"
                            + "只输出译文本身：不要解释、不要加引号、不要保留原文。"
                            + "如果是台词就保持口语，不要书面化。");

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", text);

            JSONArray messages = new JSONArray();
            messages.put(sys);
            messages.put(user);

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0);          // 翻译不要发挥
            body.put("max_tokens", 1024);

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(25000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "翻译接口返回 " + code);
                return text;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            JSONObject resp = new JSONObject(sb.toString());
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return text;
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) return text;
            String out = msg.optString("content", "");
            out = out.trim();
            // 模型偶尔还是会把引号带上
            if (out.length() > 1 && (out.startsWith("\"") && out.endsWith("\""))) {
                out = out.substring(1, out.length() - 1).trim();
            }
            return out.isEmpty() ? text : out;
        } catch (Throwable t) {
            Log.w(TAG, "翻译失败，回落原文", t);
            return text;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
