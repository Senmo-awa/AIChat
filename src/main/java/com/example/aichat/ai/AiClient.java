package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ApiProfile;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.Endpoints;
import com.example.aichat.usage.TokenUsage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 统一入口：
 * - 单模型模式：一次调用同时判断并生成回复。
 * - 双模型模式：judge 只判断是否回复，reply 负责生成内容，两套配置互相独立。
 */
public final class AiClient {
    private AiClient() {
    }

    public static CompletableFuture<AiResult> ask(ChatContext ctx, List<ConversationTurn> history) {
        String userMessage = ctx.toUserMessage();

        if (!ConfigManager.isDualModelEnabled()) {
            return dispatch(ConfigManager.singleProfile(), userMessage, history)
                    .thenApply(raw -> parseReply(raw.text(), raw.usage()));
        }

        // 判断模型只看当前事件，历史记录交给回复模型，避免误判。
        ApiProfile judgeProfile = ConfigManager.judgeProfile();
        return dispatch(judgeProfile, userMessage, List.of()).thenCompose(judgeRaw -> {
            TokenUsage judgeUsage = judgeRaw.usage();
            if (!judgeSaysReply(judgeRaw.text())) {
                return CompletableFuture.completedFuture(new AiResult(false, null, judgeUsage));
            }

            ApiProfile replyProfile = ConfigManager.replyProfile();
            return dispatch(replyProfile, userMessage, history).thenApply(replyRaw -> {
                AiResult reply = parseReplyContent(replyRaw.text(), replyRaw.usage());
                return new AiResult(
                        reply.reply(),
                        reply.content(),
                        judgeUsage.plus(replyRaw.usage())
                );
            });
        });
    }

    private static CompletableFuture<RawResult> dispatch(
            ApiProfile profile, String userMessage, List<ConversationTurn> history) {
        if ("anthropic".equals(Endpoints.resolveType(profile.apiType(), profile.apiUrl()))) {
            return AnthropicClient.ask(profile, userMessage, history);
        }
        return OpenAiClient.ask(profile, userMessage, history);
    }

    static boolean judgeSaysReply(String content) {
        try {
            if (content == null || content.isBlank()) {
                return false;
            }
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            String action = json.has("action") ? json.get("action").getAsString() : "";
            if ("reply".equals(action)) {
                return true;
            }
            JsonElement reply = json.get("reply");
            return reply != null && reply.isJsonPrimitive() && reply.getAsBoolean();
        } catch (Exception e) {
            AiChatMod.LOGGER.warn("判断模型返回内容不是合法 JSON，按不回复处理");
            return false;
        }
    }

    /**
     * 单模型：要求 {"action":"reply","content":"..."} 或 {"action":"ignore"}。
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
     * 双模型的回复阶段：只取 content；模型直接输出纯文本时也能用。
     */
    static AiResult parseReplyContent(String content, TokenUsage usage) {
        if (content == null || content.isBlank()) {
            return new AiResult(false, null, usage);
        }
        try {
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            JsonElement reply = json.get("content");
            if (reply == null || reply.isJsonNull()) {
                return new AiResult(false, null, usage);
            }
            String text = reply.getAsString().trim();
            return text.isEmpty()
                    ? new AiResult(false, null, usage)
                    : new AiResult(true, text, usage);
        } catch (Exception ignored) {
            String text = content.trim();
            return text.isEmpty()
                    ? new AiResult(false, null, usage)
                    : new AiResult(true, text, usage);
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
