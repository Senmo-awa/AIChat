package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.knowledge.KnowledgeReader;
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
import java.util.concurrent.CompletableFuture;

public final class DeepSeekClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record AiResult(boolean reply, String content) {
        public static final AiResult IGNORE = new AiResult(false, null);
    }

    private DeepSeekClient() {
    }

    public static CompletableFuture<AiResult> ask(ChatContext ctx) {
        String systemPrompt;
        try {
            String files = String.join("\n", KnowledgeReader.listFiles());
            if (files.isBlank()) {
                files = "（无）";
            }
            systemPrompt = ConfigManager.readSystemPrompt()
                    .replace("{KNOWLEDGE_FILE_LIST}", files)
                    .replace("{PERSONA_PROMPT}", ConfigManager.get().persona_prompt == null
                            ? "" : ConfigManager.get().persona_prompt);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        JsonArray messages = new JsonArray();
        messages.add(msg("system", systemPrompt));
        messages.add(msg("user", ctx.toUserMessage()));

        JsonObject body = baseBody();
        body.add("messages", messages);

        return send(body).thenCompose(first -> {
            JsonObject message = firstMessage(first);
            JsonArray toolCalls = message.has("tool_calls")
                    ? message.getAsJsonArray("tool_calls") : null;

            if (toolCalls == null || toolCalls.isEmpty()) {
                return CompletableFuture.completedFuture(parseContent(message));
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

            JsonObject secondBody = baseBody();
            secondBody.add("messages", messages);
            return send(secondBody).thenApply(resp -> parseContent(firstMessage(resp)));
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

    private static JsonObject baseBody() {
        JsonObject body = new JsonObject();
        body.addProperty("model", ConfigManager.get().model);
        body.addProperty("stream", false);

        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        body.add("thinking", thinking);

        if (ConfigManager.get().use_json_mode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }

        body.add("tools", toolDefinitions());
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

    private static CompletableFuture<JsonObject> send(JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ConfigManager.get().api_url))
                .timeout(Duration.ofSeconds(Math.max(5, ConfigManager.get().request_timeout_seconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ConfigManager.get().api_key)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        // 只记录状态码，避免泄露请求体与 API Key
                        throw new RuntimeException("API 返回 " + response.statusCode());
                    }
                    JsonElement root = JsonParser.parseString(response.body());
                    if (!root.isJsonObject()) {
                        throw new IllegalStateException("API 返回格式异常");
                    }
                    return root.getAsJsonObject();
                });
    }

    private static AiResult parseContent(JsonObject message) {
        try {
            JsonElement contentElement = message.get("content");
            if (contentElement == null || contentElement.isJsonNull()) {
                return AiResult.IGNORE;
            }
            String content = contentElement.getAsString();
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            String action = json.has("action") ? json.get("action").getAsString() : "";
            if (!"reply".equals(action)) {
                return AiResult.IGNORE;
            }
            JsonElement reply = json.get("content");
            if (reply == null || reply.isJsonNull()) {
                return AiResult.IGNORE;
            }
            String text = reply.getAsString().trim();
            return text.isEmpty() ? AiResult.IGNORE : new AiResult(true, text);
        } catch (Exception e) {
            AiChatMod.LOGGER.warn("AI 返回内容不是合法 JSON，已忽略");
            return AiResult.IGNORE;
        }
    }

    private static JsonObject msg(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }
}
