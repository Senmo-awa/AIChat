package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ApiProfile;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.Endpoints;
import com.example.aichat.knowledge.KnowledgeReader;
import com.example.aichat.state.StateManager;
import com.example.aichat.usage.TokenUsage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Chat Completions 兼容协议。
 * 覆盖 DeepSeek、通义千问、Kimi、智谱、豆包、Gemini 兼容端点、Ollama 等。
 */
public final class OpenAiClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private OpenAiClient() {
    }

    public static CompletableFuture<RawResult> ask(
            ApiProfile profile, String userMessage, List<ConversationTurn> history) {
        JsonArray messages = new JsonArray();
        messages.add(msg("system", profile.systemPrompt()));
        for (ConversationTurn turn : history) {
            if (turn.content() != null && !turn.content().isBlank()) {
                messages.add(msg(turn.role(), turn.content()));
            }
        }
        messages.add(msg("user", userMessage));

        JsonObject body = baseBody(profile);
        body.add("messages", messages);

        return send(profile, body).thenCompose(first -> {
            TokenUsage firstUsage = parseUsage(first);
            JsonObject message = firstMessage(first);
            JsonArray toolCalls = message.has("tool_calls")
                    ? message.getAsJsonArray("tool_calls") : null;

            if (toolCalls == null || toolCalls.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new RawResult(contentOf(message), firstUsage));
            }

            JsonObject toolCall = toolCalls.get(0).getAsJsonObject();
            String callId = toolCall.get("id").getAsString();
            String fileName = parseFileName(toolCall);

            String fileContent;
            try {
                if (fileName == null || fileName.isBlank()) {
                    throw new IllegalArgumentException("缺少 file_name");
                }
                fileContent = KnowledgeReader.read(fileName);
            } catch (Exception e) {
                fileContent = "读取失败: " + e.getMessage();
            }

            messages.add(message);
            JsonObject toolMsg = new JsonObject();
            toolMsg.addProperty("role", "tool");
            toolMsg.addProperty("tool_call_id", callId);
            toolMsg.addProperty("content", fileContent);
            messages.add(toolMsg);

            JsonObject secondBody = baseBody(profile);
            secondBody.add("messages", messages);
            return send(profile, secondBody).thenApply(resp -> new RawResult(
                    contentOf(firstMessage(resp)),
                    firstUsage.plus(parseUsage(resp))
            ));
        });
    }

    /**
     * OpenAI 兼容格式中 function.arguments 是 JSON 字符串，不是对象。
     */
    private static String parseFileName(JsonObject toolCall) {
        JsonObject function = toolCall.getAsJsonObject("function");
        JsonElement argsElement = function.get("arguments");
        if (argsElement == null || argsElement.isJsonNull()) {
            return null;
        }
        String raw = argsElement.isJsonPrimitive() ? argsElement.getAsString() : argsElement.toString();
        JsonObject args = JsonParser.parseString(raw).getAsJsonObject();
        JsonElement name = args.get("file_name");
        return name == null || name.isJsonNull() ? null : name.getAsString();
    }

    private static JsonObject firstMessage(JsonObject response) {
        JsonElement choices = response.get("choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
            throw new IllegalStateException("API 响应缺少 choices");
        }
        JsonObject choice = choices.getAsJsonArray().get(0).getAsJsonObject();
        JsonElement message = choice.get("message");
        if (message == null || !message.isJsonObject()) {
            throw new IllegalStateException("API 响应缺少 message");
        }
        return message.getAsJsonObject();
    }

    private static String contentOf(JsonObject message) {
        // 部分兼容端点会把 content 返回成 [{type,text}] 数组，统一交给 AiClient 处理。
        return AiClient.textOf(message.get("content"));
    }

    private static JsonObject baseBody(ApiProfile profile) {
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model());
        body.addProperty("stream", false);

        // 默认不发送 thinking：部分平台收到关闭思考的参数后不再返回 tool_calls。
        if (profile.sendThinkingField() || profile.thinkingEnabled()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", profile.thinkingEnabled() ? "enabled" : "disabled");
            body.add("thinking", thinking);
        }

        if (profile.reasoningEffort() != null && !profile.reasoningEffort().isBlank()) {
            body.addProperty("reasoning_effort", profile.reasoningEffort().trim());
        }

        if (profile.maxOutputTokens() > 0) {
            body.addProperty("max_tokens", profile.maxOutputTokens());
        }

        boolean toolsEnabled = profile.toolsEnabled();

        // 部分平台在 json_object 模式下不返回 tool_calls，启用工具时不发送 response_format。
        if (profile.jsonMode() && !toolsEnabled) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }

        if (toolsEnabled) {
            body.add("tools", toolDefinitions());
        }
        return body;
    }

    private static JsonArray toolDefinitions() {
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");

        JsonObject function = new JsonObject();
        function.addProperty("name", "read_knowledge_file");
        function.addProperty("description",
                "当需要查阅知识库文件时调用。file_name 必须是可用知识库文件列表中的文件名。");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject fileName = new JsonObject();
        fileName.addProperty("type", "string");
        fileName.addProperty("description", "知识库文件名");
        properties.add("file_name", fileName);
        parameters.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("file_name");
        parameters.add("required", required);

        function.add("parameters", parameters);
        tool.add("function", function);
        tools.add(tool);
        return tools;
    }

    private static CompletableFuture<JsonObject> send(ApiProfile profile, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Endpoints.chatCompletions(profile.apiUrl())))
                .timeout(Duration.ofSeconds(Math.max(5, ConfigManager.get().request_timeout_seconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + profile.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (StateManager.isDebug()) {
                        AiChatMod.LOGGER.info("[debug] OpenAI 兼容响应 HTTP {}：{}",
                                response.statusCode(), response.body());
                    }
                    if (response.statusCode() != 200) {
                        // 只记录状态码与错误消息，避免泄露请求体与 API Key
                        throw new RuntimeException("API 返回 " + response.statusCode()
                                + AiClient.errorDetail(response.body()));
                    }
                    JsonElement root = JsonParser.parseString(response.body());
                    if (!root.isJsonObject()) {
                        throw new IllegalStateException("API 返回格式异常");
                    }
                    return root.getAsJsonObject();
                });
    }

    private static TokenUsage parseUsage(JsonObject response) {
        JsonElement element = response.get("usage");
        if (element == null || !element.isJsonObject()) {
            return TokenUsage.ZERO;
        }
        JsonObject usage = element.getAsJsonObject();
        long prompt = readLong(usage, "prompt_tokens");
        long completion = readLong(usage, "completion_tokens");
        long total = readLong(usage, "total_tokens");
        if (total <= 0) {
            total = prompt + completion;
        }
        return new TokenUsage(prompt, completion, total);
    }

    private static long readLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }
}
