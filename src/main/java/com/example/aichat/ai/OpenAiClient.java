package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ApiProfile;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.Endpoints;
import com.example.aichat.config.ModConfig;
import com.example.aichat.knowledge.KnowledgeReader;
import com.example.aichat.search.WebSearch;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Chat Completions 兼容协议。
 * 覆盖 DeepSeek、通义千问、Kimi、智谱、豆包、Gemini 兼容端点、Ollama 等。
 */
public final class OpenAiClient {
    private static final Gson GSON = new Gson();
    private static final int RETRY_MIN_TOKENS = 1024;
    private static final int RETRY_MAX_TOKENS = 4096;
    private static final int MAX_TOOL_ROUNDS = 4;
    private static final int MAX_TOOL_CONTENT_CHARS = 6000;
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
        messages.add(msg("user", userText(profile, userMessage)));

        JsonObject body = baseBody(profile);
        body.add("messages", messages);

        return toolRound(profile, messages, body, 1, TokenUsage.ZERO, 0);
    }

    /**
     * 有的中转站/兼容端点会把 system 角色整条丢弃，模组的格式契约和人设就到不了模型。
     * 开启 system_prompt_in_user 时把系统提示词再拼进最后一条用户消息，保证契约一定被看到。
     */
    private static String userText(ApiProfile profile, String userMessage) {
        String prompt = profile.systemPrompt();
        if (!profile.systemInUserMessage() || prompt == null || prompt.isBlank()) {
            return userMessage;
        }
        return "【以下为系统规则，必须严格遵守】\n" + prompt.trim()
                + "\n\n【当前事件】\n" + userMessage;
    }

    /**
     * 工具调用可能不止一轮：模型读完一个文件后可能还要读另一个。把 tool_calls 直接当最终回复
     * 会得到空 content，解析就失败了。这里循环多轮，每轮回灌所有 tool_call 的结果；
     * 超过 MAX_TOOL_ROUNDS 后再发一次不带工具的请求，逼模型直接给出最终 JSON。
     */
    private static CompletableFuture<RawResult> toolRound(
            ApiProfile profile, JsonArray messages, JsonObject body, int round, TokenUsage used,
            int searchUsed) {
        boolean lastChance = round > MAX_TOOL_ROUNDS;
        JsonObject requestBody = body;
        if (lastChance && requestBody.has("tools")) {
            requestBody = body.deepCopy();
            requestBody.remove("tools");
            AiChatMod.LOGGER.info("工具调用已达 {} 轮，最后一次请求不再提供工具，要求模型直接给出结论", MAX_TOOL_ROUNDS);
        }
        return sendWithRetry(profile, requestBody).thenCompose(attempts -> {
            TokenUsage usage = used.plus(totalUsage(attempts));
            JsonObject response = attempts.get(attempts.size() - 1);
            JsonObject message = firstMessage(response);
            JsonElement toolCalls = message.get("tool_calls");
            if (toolCalls == null || !toolCalls.isJsonArray() || toolCalls.getAsJsonArray().isEmpty()) {
                return CompletableFuture.completedFuture(new RawResult(contentOf(message), usage));
            }
            if (lastChance) {
                AiChatMod.LOGGER.warn("模型在没有工具的情况下仍返回 tool_calls，本次按不回复处理");
                return CompletableFuture.completedFuture(new RawResult(contentOf(message), usage));
            }

            messages.add(message);
            // 捕获变量不能改，用一个一格数组当可变计数器
            int[] searchBudget = {searchUsed};
            for (JsonElement element : toolCalls.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject toolCall = element.getAsJsonObject();
                String callId = toolCall.has("id") && !toolCall.get("id").isJsonNull()
                        ? toolCall.get("id").getAsString() : "";
                String toolContent;
                if ("web_search".equals(functionName(toolCall))) {
                    if (searchBudget[0] > 0) {
                        // 防刷：一次对话只允许搜一次，避免模型反复搜索把 token 和接口额度烧光
                        toolContent = "本次对话已经搜索过一次，请直接基于上面的搜索结果回答，不要重复搜索。";
                    } else {
                        toolContent = truncate(runSearch(toolCall));
                        searchBudget[0]++;
                    }
                } else {
                    try {
                        String fileName = stringArg(toolCall, "file_name");
                        if (fileName == null || fileName.isBlank()) {
                            throw new IllegalArgumentException("缺少 file_name");
                        }
                        toolContent = truncate(KnowledgeReader.read(fileName));
                    } catch (Exception e) {
                        toolContent = "读取失败: " + e.getMessage();
                    }
                }
                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("tool_call_id", callId);
                toolMsg.addProperty("content", toolContent);
                messages.add(toolMsg);
            }

            JsonObject nextBody = baseBody(profile);
            nextBody.add("messages", messages);
            int nextRound = round + 1;
            return toolRound(profile, messages, nextBody, nextRound, usage, searchBudget[0]);
        });
    }

    /**
     * 知识库文件可能很长，全量回灌会吃掉大量 prompt token，这里做上限截断。
     */
    private static String truncate(String content) {
        if (content == null || content.length() <= MAX_TOOL_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_TOOL_CONTENT_CHARS) + "\n…（文件过长，已截断）";
    }

    private static String functionName(JsonObject toolCall) {
        JsonElement function = toolCall.get("function");
        if (function == null || !function.isJsonObject()) {
            return "";
        }
        JsonElement name = function.getAsJsonObject().get("name");
        return name == null || name.isJsonNull() ? "" : name.getAsString();
    }

    /**
     * OpenAI 兼容格式中 function.arguments 是 JSON 字符串，不是对象。
     * 解析失败一律当没有该参数处理，交给调用方报错。
     */
    private static String stringArg(JsonObject toolCall, String field) {
        JsonObject function = toolCall.getAsJsonObject("function");
        if (function == null) {
            return null;
        }
        JsonElement argsElement = function.get("arguments");
        if (argsElement == null || argsElement.isJsonNull()) {
            return null;
        }
        String raw = argsElement.isJsonPrimitive() ? argsElement.getAsString() : argsElement.toString();
        JsonObject args;
        try {
            args = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        JsonElement value = args.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * 真正去联网搜索。失败不重试、不抛给上层：把失败原因当 tool 结果回灌，
     * 让模型照实告诉玩家"没搜到"，比让它瞎编强。
     */
    private static String runSearch(JsonObject toolCall) {
        ModConfig config = ConfigManager.get();
        String query = stringArg(toolCall, "query");
        if (query == null || query.isBlank()) {
            return "搜索失败: 缺少 query 参数";
        }
        long start = System.currentTimeMillis();
        try {
            String result = WebSearch.search(
                    config.search_provider,
                    config.search_api_url,
                    config.search_api_key,
                    query,
                    config.search_max_results,
                    config.search_timeout_seconds);
            if (StateManager.isDebug()) {
                AiChatMod.LOGGER.info("[debug] 联网搜索 provider={} query={} 用时={}ms",
                        config.search_provider, ConsoleSafe.escape(query),
                        System.currentTimeMillis() - start);
            }
            return result;
        } catch (Exception e) {
            AiChatMod.LOGGER.warn("联网搜索失败（provider={}）：{}",
                    config.search_provider, ConsoleSafe.escape(e.getMessage()));
            return "搜索失败: " + e.getMessage() + "。请如实告诉玩家这次没搜到，不要编造结果。";
        }
    }

    /**
     * 推理模型的思考内容也计入 max_tokens。预算给小了会得到 finish_reason=length 且 content 为空的响应，
     * 判断环节就会永远"忽略"。这里遇到这种情况自动放大一次预算重试，避免整个模组静默失灵。
     */
    private static CompletableFuture<List<JsonObject>> sendWithRetry(ApiProfile profile, JsonObject body) {
        List<JsonObject> attempts = new ArrayList<>();
        return send(profile, body).thenCompose(first -> {
            attempts.add(first);
            int limit = profile.maxOutputTokens();
            if (limit <= 0 || !truncatedWithoutContent(first)) {
                return CompletableFuture.completedFuture(attempts);
            }
            int retryLimit = Math.min(RETRY_MAX_TOKENS, Math.max(RETRY_MIN_TOKENS, limit * 4));
            if (retryLimit <= limit) {
                return CompletableFuture.completedFuture(attempts);
            }
            AiChatMod.LOGGER.warn(
                    "模型输出被 max_tokens={} 截断且 content 为空（finish_reason=length，推理占满了预算），"
                            + "改用 max_tokens={} 重试一次",
                    limit, retryLimit);
            JsonObject retryBody = body.deepCopy();
            retryBody.addProperty("max_tokens", retryLimit);
            return send(profile, retryBody).thenApply(second -> {
                attempts.add(second);
                if (truncatedWithoutContent(second)) {
                    AiChatMod.LOGGER.warn(
                            "重试后 content 仍为空（max_tokens={} 依旧被推理占满），本次按不回复处理；"
                                    + "请调大 system.json 里的 max_output_tokens，或关闭该模型的思考模式",
                            retryLimit);
                }
                return attempts;
            });
        });
    }

    private static boolean truncatedWithoutContent(JsonObject response) {
        JsonObject choice = firstChoice(response);
        JsonElement finish = choice == null ? null : choice.get("finish_reason");
        if (finish == null || !finish.isJsonPrimitive()
                || !"length".equals(finish.getAsString())) {
            return false;
        }
        JsonObject message = firstMessage(response);
        JsonElement toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isJsonArray() && !toolCalls.getAsJsonArray().isEmpty()) {
            return false;
        }
        String content = contentOf(message);
        return content == null || content.isBlank();
    }

    private static TokenUsage totalUsage(List<JsonObject> attempts) {
        TokenUsage usage = TokenUsage.ZERO;
        for (JsonObject attempt : attempts) {
            usage = usage.plus(parseUsage(attempt));
        }
        return usage;
    }

    private static JsonObject firstChoice(JsonObject response) {
        JsonElement choices = response.get("choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonElement choice = choices.getAsJsonArray().get(0);
        return choice.isJsonObject() ? choice.getAsJsonObject() : null;
    }    private static JsonObject firstMessage(JsonObject response) {
        JsonObject choice = firstChoice(response);
        if (choice == null) {
            throw new IllegalStateException("API 响应缺少 choices");
        }
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
        List<String> files = KnowledgeReader.listFiles();
        String list = files.isEmpty() ? "（知识库为空）" : String.join("、", files);

        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");

        JsonObject function = new JsonObject();
        function.addProperty("name", "read_knowledge_file");
        // 把真实文件名写进工具描述：否则模型只能瞎猜（小墨.md / knowledge.md 之类），全部读取失败。
        function.addProperty("description",
                "当需要查阅知识库文件时调用。file_name 只能取以下之一：" + list);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject fileName = new JsonObject();
        fileName.addProperty("type", "string");
        fileName.addProperty("description", "知识库文件名，可选值：" + list);
        properties.add("file_name", fileName);
        parameters.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("file_name");
        parameters.add("required", required);

        function.add("parameters", parameters);
        tool.add("function", function);
        tools.add(tool);

        if (searchAvailable()) {
            tools.add(webSearchTool());
        }
        return tools;
    }

    /**
     * 搜索工具只在配置齐全时才注册：宁可让模型看不到它，也不要让玩家看到"搜索总是失败"。
     */
    private static boolean searchAvailable() {
        ModConfig config = ConfigManager.get();
        if (!config.search_enabled) {
            return false;
        }
        String provider = config.search_provider == null
                ? "" : config.search_provider.trim().toLowerCase(Locale.ROOT);
        if (provider.isBlank()) {
            return false;
        }
        if ("searxng".equals(provider)) {
            return config.search_api_url != null && !config.search_api_url.isBlank();
        }
        if (WebSearch.requiresApiKey(provider)) {
            return config.search_api_key != null && !config.search_api_key.isBlank();
        }
        return true;
    }

    private static JsonObject webSearchTool() {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");

        JsonObject function = new JsonObject();
        function.addProperty("name", "web_search");
        function.addProperty("description",
                "联网搜索实时信息：新闻、天气、价格、赛事、人物近况等当前事实。"
                        + "只有确实需要外部最新信息时才调用，一次对话最多搜一次。"
                        + "搜不到就照实说，不要编造。");

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "搜索关键词，用简洁的短语，不要带整段聊天内容");
        properties.add("query", query);
        parameters.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("query");
        parameters.add("required", required);

        function.add("parameters", parameters);
        tool.add("function", function);
        return tool;
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
                                response.statusCode(), ConsoleSafe.escape(response.body()));
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
