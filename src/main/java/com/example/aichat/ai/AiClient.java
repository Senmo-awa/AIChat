package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.knowledge.KnowledgeReader;
import com.example.aichat.usage.TokenUsage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 统一入口：按 api_type / api_url 选择 OpenAI 兼容协议或 Anthropic Messages 协议。
 */
public final class AiClient {
    private AiClient() {
    }

    public static CompletableFuture<AiResult> ask(ChatContext ctx) {
        if (ConfigManager.isAnthropic()) {
            return AnthropicClient.ask(ctx);
        }
        return OpenAiClient.ask(ctx);
    }

    static String buildSystemPrompt() throws IOException {
        String files = String.join("\n", KnowledgeReader.listFiles());
        if (files.isBlank()) {
            files = "（无）";
        }
        String persona;
        try {
            persona = ConfigManager.readPersonaPrompt();
        } catch (Exception e) {
            persona = "";
            AiChatMod.LOGGER.warn("读取人设文件失败: {}", e.getMessage());
        }
        return ConfigManager.readSystemPrompt()
                .replace("{KNOWLEDGE_FILE_LIST}", files)
                .replace("{PERSONA_PROMPT}", persona);
    }

    /**
     * 两个协议最终都归一成 {"action":"reply","content":"..."} 或 {"action":"ignore"}。
     */
    static AiResult parseReply(String content, TokenUsage usage) {
        try {
            if (content == null || content.isBlank()) {
                return new AiResult(false, null, usage);
            }
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            String action = json.has("action") ? json.get("action").getAsString() : "";
            if (!"reply".equals(action)) {
                return new AiResult(false, null, usage);
            }
            JsonElement reply = json.get("content");
            if (reply == null || reply.isJsonNull()) {
                return new AiResult(false, null, usage);
            }
            String text = reply.getAsString().trim();
            return text.isEmpty()
                    ? new AiResult(false, null, usage)
                    : new AiResult(true, text, usage);
        } catch (Exception e) {
            AiChatMod.LOGGER.warn("AI 返回内容不是合法 JSON，已忽略");
            return new AiResult(false, null, usage);
        }
    }

    /**
     * 从错误响应里取可读信息，取不到就返回空串。不会打印请求体或 Key。
     */
    static String errorDetail(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement error = root.get("error");
            if (error != null && error.isJsonObject()) {
                JsonElement message = error.getAsJsonObject().get("message");
                if (message != null && !message.isJsonNull()) {
                    return ": " + message.getAsString();
                }
            }
            JsonElement message = root.get("message");
            if (message != null && !message.isJsonNull()) {
                return ": " + message.getAsString();
            }
        } catch (Exception ignored) {
            // 错误响应不是 JSON 时只保留状态码
        }
        return "";
    }
}
