package com.example.aichat.ai;

import com.example.aichat.config.ApiProfile;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.Endpoints;
import com.example.aichat.knowledge.KnowledgeReader;
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
import java.util.concurrent.CompletableFuture;

/**
 * Anthropic Messages API（POST /v1/messages，x-api-key 认证）。
 * 与 OpenAI 的主要差异：system 独立字段、tools 用 input_schema、
 * 回复在 content 数组里、工具结果用 tool_result block、usage 是 input/output。
 */
public final class AnthropicClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private AnthropicClient() {
    }

    public static CompletableFuture<RawResult> ask(ApiProfile profile, String userMessage) {
        JsonArray messages = new JsonArray();
        messages.add(msg("user", userMessage));

        JsonObject body = baseBody(profile);
        body.add("messages", messages);

        return send(profile, body).thenCompose(first -> {
            TokenUsage firstUsage = parseUsage(first);
            JsonObject toolUse = findToolUse(first);
            if (toolUse == null) {
                return CompletableFuture.completedFuture(
                        new RawResult(extractText(first), firstUsage));
            }

            String toolId = stringOf(toolUse, "id");
            String fileName = parseFileName(toolUse);

            String fileContent;
            try {
                if (fileName == null || fileName.isBlank()) {
                    throw new IllegalArgumentException("缺少 file_name");
                }
                fileContent = KnowledgeReader.read(fileName);
            } catch (Exception e) {
                fileContent = "读取失败: " + e.getMessage();
            }

            // assistant 的 content 原样回传（可能含 thinking 与 tool_use block）
            JsonObject assistant = new JsonObject();
            assistant.addProperty("role", "assistant");
            assistant.add("content", first.get("content"));
            messages.add(assistant);

            JsonObject toolResult = new JsonObject();
            toolResult.addProperty("type", "tool_result");
            toolResult.addProperty("tool_use_id", toolId);
            toolResult.addProperty("content", fileContent);

            JsonArray resultBlocks = new JsonArray();
            resultBlocks.add(toolResult);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.add("content", resultBlocks);
            messages.add(user);

            JsonObject secondBody = baseBody(profile);
            secondBody.add("messages", messages);
            return send(profile, secondBody).thenApply(resp -> new RawResult(
                    extractText(resp),
                    firstUsage.plus(parseUsage(resp))
            ));
        });
    }

    private static JsonObject baseBody(ApiProfile profile) {
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model());

        int maxTokens = profile.maxOutputTokens();
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("system", profile.systemPrompt());

        if (profile.thinkingEnabled()) {
            int budget = profile.thinkingBudgetTokens();
            if (budget <= 0) {
                budget = 1024;
            }
            // Anthropic 要求 budget_tokens 小于 max_tokens
            budget = Math.min(budget, maxTokens - 1);
            if (budget > 0) {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "enabled");
                thinking.addProperty("budget_tokens", budget);
                body.add("thinking", thinking);
            }
        }

        if (profile.toolsEnabled()) {
            body.add("tools", toolDefinitions());
        }
        return body;
    }

    private static JsonArray toolDefinitions() {
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "read_knowledge_file");
        tool.addProperty("description",
                "当需要查阅知识库文件时调用。file_name 必须是可用知识库文件列表中的文件名。");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject fileName = new JsonObject();
        fileName.addProperty("type", "string");
        fileName.addProperty("description", "知识库文件名");
        properties.add("file_name", fileName);
        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("file_name");
        schema.add("required", required);

        tool.add("input_schema", schema);
        tools.add(tool);
        return tools;
    }

    private static JsonObject findToolUse(JsonObject response) {
        JsonElement contentElement = response.get("content");
        if (contentElement == null || !contentElement.isJsonArray()) {
            return null;
        }
        for (JsonElement element : contentElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            JsonElement type = block.get("type");
            if (type != null && "tool_use".equals(type.getAsString())) {
                return block;
            }
        }
        return null;
    }

    /**
     * Anthropic 的 tool_use.input 已经是 JSON 对象，不需要再解析字符串。
     */
    private static String parseFileName(JsonObject toolUse) {
        JsonElement inputElement = toolUse.get("input");
        if (inputElement == null || !inputElement.isJsonObject()) {
            return null;
        }
        JsonElement name = inputElement.getAsJsonObject().get("file_name");
        return name == null || name.isJsonNull() ? null : name.getAsString();
    }

    private static String extractText(JsonObject response) {
        StringBuilder text = new StringBuilder();
        JsonElement contentElement = response.get("content");
        if (contentElement != null && contentElement.isJsonArray()) {
            for (JsonElement element : contentElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                JsonElement type = block.get("type");
                if (type == null || !"text".equals(type.getAsString())) {
                    continue;
                }
                JsonElement value = block.get("text");
                if (value != null && !value.isJsonNull()) {
                    text.append(value.getAsString());
                }
            }
        }
        return text.toString();
    }

    private static TokenUsage parseUsage(JsonObject response) {
        JsonElement element = response.get("usage");
        if (element == null || !element.isJsonObject()) {
            return TokenUsage.ZERO;
        }
        JsonObject usage = element.getAsJsonObject();
        long input = readLong(usage, "input_tokens");
        long output = readLong(usage, "output_tokens");
        return new TokenUsage(input, output, input + output);
    }

    private static long readLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }

    private static String stringOf(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static CompletableFuture<JsonObject> send(ApiProfile profile, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Endpoints.messages(profile.apiUrl())))
                .timeout(Duration.ofSeconds(Math.max(5, ConfigManager.get().request_timeout_seconds)))
                .header("Content-Type", "application/json")
                .header("x-api-key", profile.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
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

    private static JsonObject msg(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }
}
