package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ApiProfile;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.Endpoints;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
import com.example.aichat.tool.Tool;
import com.example.aichat.tool.ToolRun;
import com.example.aichat.tool.Tools;
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
    private static final int MAX_TOOL_ROUNDS = 4;

    private AnthropicClient() {
    }

    public static CompletableFuture<RawResult> ask(
            ApiProfile profile, String userMessage, List<ConversationTurn> history, ToolRun run) {
        JsonArray messages = new JsonArray();
        for (ConversationTurn turn : history) {
            addMessage(messages, turn.role(), turn.content());
        }
        addMessage(messages, "user", userText(profile, userMessage));

        List<Tool> tools = Tools.active(
                profile.toolsEnabled(), profile.memoryEnabled(), run != null && run.serverToolsAllowed());
        return toolRound(profile, messages, 1, TokenUsage.ZERO, tools, run == null ? new ToolRun() : run);
    }

    /**
     * 工具调用可能不止一轮（读知识库 → 查记忆 → 再回答）。每轮把 assistant 的 content 原样回传，
     * 再把所有 tool_use 的结果作为一条 user 消息回灌；超过轮数上限后发一次不带工具的请求收尾。
     */
    private static CompletableFuture<RawResult> toolRound(
            ApiProfile profile, JsonArray messages, int round, TokenUsage used,
            List<Tool> tools, ToolRun run) {
        boolean lastChance = round > MAX_TOOL_ROUNDS;
        JsonObject body = baseBody(profile, lastChance ? List.of() : tools);
        if (lastChance && !tools.isEmpty()) {
            AiChatMod.LOGGER.info("工具调用已达 {} 轮，最后一次请求不再提供工具，要求模型直接给出结论", MAX_TOOL_ROUNDS);
        }
        body.add("messages", messages);

        return send(profile, body).thenCompose(response -> {
            TokenUsage usage = used.plus(parseUsage(response));
            JsonArray toolUses = findToolUses(response);
            if (toolUses.isEmpty()) {
                return CompletableFuture.completedFuture(new RawResult(extractText(response), usage));
            }
            if (lastChance) {
                AiChatMod.LOGGER.warn("模型在没有工具的情况下仍返回 tool_use，本次按不回复处理");
                return CompletableFuture.completedFuture(new RawResult(extractText(response), usage));
            }

            // assistant 的 content 原样回传（可能含 thinking 与 tool_use block）
            JsonObject assistant = new JsonObject();
            assistant.addProperty("role", "assistant");
            assistant.add("content", response.get("content"));
            messages.add(assistant);

            JsonArray resultBlocks = new JsonArray();
            for (JsonElement element : toolUses) {
                JsonObject toolUse = element.getAsJsonObject();
                String toolId = stringOf(toolUse, "id");
                String toolName = stringOf(toolUse, "name");
                JsonElement input = toolUse.get("input");
                JsonObject arguments = input != null && input.isJsonObject()
                        ? input.getAsJsonObject() : new JsonObject();

                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("type", "tool_result");
                toolResult.addProperty("tool_use_id", toolId);
                toolResult.addProperty("content", Tools.invoke(tools, toolName, arguments, run));
                resultBlocks.add(toolResult);
            }

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.add("content", resultBlocks);
            messages.add(user);

            return toolRound(profile, messages, round + 1, usage, tools, run);
        });
    }

    /**
     * 与 OpenAI 兼容端点保持一致的兜底：部分中转会丢掉 system，这里把系统提示词再拼进用户消息。
     */
    private static String userText(ApiProfile profile, String userMessage) {
        String prompt = profile.systemPrompt();
        if (profile.systemInUserMessage() && prompt != null && !prompt.isBlank()) {
            return "【以下为系统规则，必须严格遵守】\n" + prompt.trim()
                    + "\n\n【当前事件】\n" + userMessage;
        }
        return userMessage;
    }

    private static JsonObject baseBody(ApiProfile profile, List<Tool> tools) {
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

        if (!tools.isEmpty()) {
            body.add("tools", toolDefinitions(tools));
        }
        return body;
    }

    /**
     * Anthropic 的工具格式：name / description / input_schema 平铺在 tool 对象上。
     */
    private static JsonArray toolDefinitions(List<Tool> tools) {
        JsonArray array = new JsonArray();
        for (Tool tool : tools) {
            JsonObject definition = new JsonObject();
            definition.addProperty("name", tool.name());
            definition.addProperty("description", tool.description());
            definition.add("input_schema", tool.parameters());
            array.add(definition);
        }
        return array;
    }

    /** 一次响应里可能同时有多个 tool_use block，全部回灌。 */
    private static JsonArray findToolUses(JsonObject response) {
        JsonArray result = new JsonArray();
        JsonElement contentElement = response.get("content");
        if (contentElement == null || !contentElement.isJsonArray()) {
            return result;
        }
        for (JsonElement element : contentElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            JsonElement type = block.get("type");
            if (type != null && "tool_use".equals(type.getAsString())) {
                result.add(block);
            }
        }
        return result;
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
                    if (StateManager.isDebug()) {
                        AiChatMod.LOGGER.info("[debug] Anthropic 响应 HTTP {}：{}",
                                response.statusCode(), ConsoleSafe.escape(response.body()));
                    }
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

    /**
     * Anthropic 要求 user / assistant 交替，连续同角色消息在这里合并。
     */
    private static void addMessage(JsonArray messages, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!messages.isEmpty()) {
            JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
            JsonElement lastRole = last.get("role");
            if (lastRole != null && role.equals(lastRole.getAsString())) {
                String merged = last.get("content").getAsString() + "\n" + content;
                last.addProperty("content", merged);
                return;
            }
        }
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        messages.add(object);
    }
}
