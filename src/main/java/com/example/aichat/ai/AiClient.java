package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ApiProfile;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.Endpoints;
import com.example.aichat.state.StateManager;
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

        // 判断模型也要带历史：提示词里的"结合上下文""玩家在和别人聊天"等规则依赖上下文，
        // 只给当前事件会让 judge 变成盲判，和单模型模式的表现不一致。
        ApiProfile judgeProfile = ConfigManager.judgeProfile();
        return dispatch(judgeProfile, userMessage, history).thenCompose(judgeRaw -> {
            TokenUsage judgeUsage = judgeRaw.usage();
            boolean shouldReply = judgeSaysReply(judgeRaw.text());
            if (StateManager.isDebug()) {
                AiChatMod.LOGGER.info("[debug] 判断模型结论：{}（事件：{}，历史条数：{}）",
                        shouldReply ? "reply" : "ignore", ctx.eventType(), history.size());
            }
            if (!shouldReply) {
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

    private static final int LOG_PREVIEW_LENGTH = 200;

    static boolean judgeSaysReply(String content) {
        String jsonText = extractJson(content);
        if (jsonText == null) {
            if (content == null || content.isBlank()) {
                AiChatMod.LOGGER.warn("判断模型返回空内容，按不回复处理");
            } else {
                AiChatMod.LOGGER.warn("判断模型返回内容不是合法 JSON，按不回复处理：{}", preview(content));
            }
            return false;
        }
        try {
            JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
            String action = optionalString(json, "action");
            if ("reply".equals(action)) {
                return true;
            }
            JsonElement reply = json.get("reply");
            return reply != null && reply.isJsonPrimitive() && reply.getAsBoolean();
        } catch (Exception e) {
            AiChatMod.LOGGER.warn("判断模型返回内容不是合法 JSON，按不回复处理：{}", preview(content));
            return false;
        }
    }

    /**
     * 单模型：优先读 {"action":"reply","content":"..."} 或 {"action":"ignore"}。
     * 为了兼容只写 {"content":"..."} 的自定义提示词，缺少 action 时也按回复处理。
     */
    static AiResult parseReply(String content, TokenUsage usage) {
        String jsonText = extractJson(content);
        if (jsonText == null) {
            if (content != null && !content.isBlank()) {
                AiChatMod.LOGGER.warn("AI 返回内容不是合法 JSON，已忽略：{}", preview(content));
            }
            return new AiResult(false, null, usage);
        }
        try {
            JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
            String action = optionalString(json, "action");
            if ("ignore".equals(action)) {
                return new AiResult(false, null, usage);
            }
            String text = textOf(json.get("content"));
            if (text == null || text.isEmpty()) {
                if ("reply".equals(action)) {
                    AiChatMod.LOGGER.warn("AI 返回了 reply 但 content 为空，已忽略：{}", preview(content));
                }
                return new AiResult(false, null, usage);
            }
            return new AiResult(true, text, usage);
        } catch (Exception e) {
            AiChatMod.LOGGER.warn("AI 返回内容不是合法 JSON，已忽略：{}", preview(content));
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
        String jsonText = extractJson(content);
        if (jsonText != null) {
            try {
                JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
                String text = textOf(json.get("content"));
                if (text != null && !text.isEmpty()) {
                    return new AiResult(true, text, usage);
                }
            } catch (Exception ignored) {
                // 落到下面的纯文本兜底
            }
        }
        String text = content.trim();
        return text.isEmpty()
                ? new AiResult(false, null, usage)
                : new AiResult(true, text, usage);
    }

    /**
     * 从模型输出里取出 JSON 对象：容忍 ```json 代码块和 JSON 前后的多余文字。
     */
    static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline > 0) {
                text = text.substring(newline + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /**
     * content 一般是字符串；部分兼容端点会返回 [{type,text}] 数组，这里拼起来。
     */
    static String textOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString().trim();
        }
        if (element.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement part : element.getAsJsonArray()) {
                if (part.isJsonObject()) {
                    String text = optionalString(part.getAsJsonObject(), "text");
                    if (!text.isEmpty()) {
                        builder.append(text);
                    }
                } else if (part.isJsonPrimitive()) {
                    builder.append(part.getAsString());
                }
            }
            return builder.toString().trim();
        }
        return null;
    }

    private static String optionalString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
                ? ""
                : element.getAsString();
    }

    /**
     * 只截取开头用于日志，避免刷屏；换行压成空格。
     */
    private static String preview(String content) {
        if (content == null) {
            return "null";
        }
        String text = content.replace("\r", " ").replace("\n", " ").trim();
        return text.length() <= LOG_PREVIEW_LENGTH
                ? text
                : text.substring(0, LOG_PREVIEW_LENGTH) + "…";
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
