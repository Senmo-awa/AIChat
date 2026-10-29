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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Chat Completions 兼容协议。
 * 覆盖 DeepSeek、通义千问、Kimi、智谱、豆包、Gemini 兼容端点、Ollama 等。
 */
public final class OpenAiClient {
    private static final Gson GSON = new Gson();
    private static final int RETRY_MIN_TOKENS = 1024;
    private static final int RETRY_MAX_TOKENS = 4096;
    private static final int MAX_TOOL_ROUNDS = 4;
    /** 网络瞬时失败的重试次数与间隔。 */
    private static final int MAX_NETWORK_ATTEMPTS = 2;
    private static final int NETWORK_RETRY_DELAY_SECONDS = 2;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private OpenAiClient() {
    }

    /**
     * @param allowProseRepair 是否允许「纯文本脱轨修复」：true 用于要发出去的回复（单模型 / 双模型的 reply 阶段）；
     *                         false 用于判断模型——judge 只该回答 reply/ignore，把它的自然语言当回复会用错。
     */
    public static CompletableFuture<RawResult> ask(
            ApiProfile profile, String userMessage, List<ConversationTurn> history, ToolRun run,
            boolean allowProseRepair) {
        JsonArray messages = new JsonArray();
        messages.add(msg("system", profile.systemPrompt()));
        for (ConversationTurn turn : history) {
            if (turn.content() != null && !turn.content().isBlank()) {
                messages.add(msg(turn.role(), turn.content()));
            }
        }
        messages.add(msg("user", userText(profile, userMessage)));

        List<Tool> tools = Tools.active(
                profile.toolsEnabled(), profile.memoryEnabled(), run != null && run.serverToolsAllowed());
        return toolRound(profile, messages, 1, TokenUsage.ZERO,
                tools, run == null ? new ToolRun() : run, false, allowProseRepair);
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
     * 工具调用可能不止一轮：模型读完一个文件后可能还要读另一个，写完记忆后可能还要确认。
     * 把 tool_calls 直接当最终回复会得到空 content，解析就失败了。这里循环多轮，每轮回灌所有
     * tool_call 的结果；超过 MAX_TOOL_ROUNDS 后再发一次不带工具的请求，逼模型直接给出最终 JSON。
     */
    private static CompletableFuture<RawResult> toolRound(
            ApiProfile profile, JsonArray messages, int round, TokenUsage used,
            List<Tool> tools, ToolRun run, boolean usedTools, boolean allowProseRepair) {
        // 轮次用尽后不再直接抽掉 tools：实测抽掉之后模型会把工具调用写成私有标记
        // （DeepSeek 的 <｜｜DSML｜｜ calls>），模组解析不了只能丢弃，玩家端就是「AI 不说话」。
        // 所以先用「保留工具声明 + 明确要求收尾」温柔收一次，再不行才真正回收工具。
        boolean exhausted = round > MAX_TOOL_ROUNDS;
        boolean stripTools = round > MAX_TOOL_ROUNDS + 1;
        if (exhausted) {
            messages.add(msg("user", stripTools
                    ? "【系统】工具已经全部回收，本轮只能直接输出最终 JSON，不要再调用工具，也不要输出任何工具调用标记。"
                    : "【系统】工具调用次数已达上限，本轮不要再调用工具，请直接用 JSON 给出最终结论。"));
            AiChatMod.LOGGER.info("工具调用已达 {} 轮，{}", MAX_TOOL_ROUNDS,
                    stripTools ? "本轮回收工具，要求直接给出结论" : "本轮保留工具但要求收尾");
        }

        JsonObject body = baseBody(profile, stripTools ? List.of() : tools);
        body.add("messages", messages);

        return sendWithRetry(profile, body).thenCompose(attempts -> {
            TokenUsage usage = used.plus(totalUsage(attempts));
            JsonObject response = attempts.get(attempts.size() - 1);
            JsonObject message = firstMessage(response);
            JsonElement toolCalls = message.get("tool_calls");
            boolean hasToolCalls = toolCalls != null && toolCalls.isJsonArray()
                    && !toolCalls.getAsJsonArray().isEmpty();
            if (!hasToolCalls) {
                String content = contentOf(message);
                // 模型把最终回答写成自然语言（JSON 契约脱轨）：先补一次「改写」请求，仍不听话才兜底原文。
                // 不限定 usedTools —— 实测不调工具的普通聊天也会脱轨（22:25 那次一次工具都没调）。
                if (allowProseRepair && Fallbacks.replyJsonFromProse(content) != null) {
                    return repairRound(profile, messages, content, usage, tools);
                }
                return CompletableFuture.completedFuture(finalResult(message, usage, usedTools));
            }
            if (stripTools) {
                AiChatMod.LOGGER.warn("模型在没有工具的情况下仍返回 tool_calls，本次按不回复处理");
                return CompletableFuture.completedFuture(finalResult(message, usage, usedTools));
            }
            if (exhausted) {
                // 收尾指令被无视，仍要调工具：追加的 user 提示已经写进 messages，
                // 直接进下一轮（下一轮会回收工具），不把这条 tool_calls 消息并进历史。
                return toolRound(profile, messages, round + 1, usage, tools, run, usedTools,
                        allowProseRepair);
            }

            messages.add(message);
            for (JsonElement element : toolCalls.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject toolCall = element.getAsJsonObject();
                String callId = toolCall.has("id") && !toolCall.get("id").isJsonNull()
                        ? toolCall.get("id").getAsString() : "";
                String toolContent = Tools.invoke(tools, functionName(toolCall), arguments(toolCall), run);

                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("tool_call_id", callId);
                toolMsg.addProperty("content", toolContent);
                messages.add(toolMsg);
            }

            // 每轮回灌后钉一次格式契约：长工具链之后小模型极易脱轨成自然语言
            messages.add(msg("user", Fallbacks.TOOL_FOLLOWUP_REMINDER));

            return toolRound(profile, messages, round + 1, usage, tools, run, true,
                    allowProseRepair);
        });
    }

    /**
     * 脱轨修复轮：模型把最终回答写成了自然语言而不是 JSON。
     * 原样带上它那句话，再要求改写成 JSON；工具声明保留着（抽掉工具反而会让它写私有标记）。
     * 修复只做一次——仍不听话就用原文兜底，绝不在这里无限续命。
     */
    private static CompletableFuture<RawResult> repairRound(
            ApiProfile profile, JsonArray messages, String prose, TokenUsage usage, List<Tool> tools) {
        AiChatMod.LOGGER.warn("模型没按 JSON 契约回复（纯文本 {} 字），补发一次改写请求：{}",
                prose.length(), ConsoleSafe.escape(prosePreview(prose)));

        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        assistant.addProperty("content", prose);
        messages.add(assistant);
        messages.add(msg("user", Fallbacks.JSON_REPAIR_REMINDER));

        JsonObject body = baseBody(profile, tools);
        body.add("messages", messages);
        return sendWithRetry(profile, body).thenApply(attempts -> {
            TokenUsage total = usage.plus(totalUsage(attempts));
            JsonObject message = firstMessage(attempts.get(attempts.size() - 1));
            JsonElement toolCalls = message.get("tool_calls");
            boolean hasToolCalls = toolCalls != null && toolCalls.isJsonArray()
                    && !toolCalls.getAsJsonArray().isEmpty();
            String repaired = contentOf(message);
            if (!hasToolCalls && Fallbacks.isJsonObject(repaired)) {
                AiChatMod.LOGGER.info("改写请求成功，本轮按改写后的 JSON 回复");
                return new RawResult(repaired, total);
            }
            AiChatMod.LOGGER.warn("改写请求仍没给出 JSON（{}），本轮按原文兜底",
                    hasToolCalls ? "又返回了 tool_calls" : "仍然是纯文本");
            return fallbackFromProse(prose, total);
        });
    }

    /** 原文兜底：把模型那句自然语言包成合法回复；连这句都不可用才退回 fallback_reply。 */
    private static RawResult fallbackFromProse(String prose, TokenUsage usage) {
        String salvaged = Fallbacks.replyJsonFromProse(prose);
        return new RawResult(salvaged != null ? salvaged : Fallbacks.replyJsonOrNull(), usage);
    }

    /**
     * 收尾：把模型最后一条消息转成最终结果。
     * 用过工具却没拿到可用 JSON（空内容 / 私有工具标记）时，改用配置里的兜底话术；
     * 拿到的是「纯自然语言」（有内容但不是 JSON）时，把原文包成合法回复救回来——
     * 这条路径以前会一路走到 AiClient 的「不是合法 JSON，已忽略」，玩家端就一个字都收不到。
     */
    private static RawResult finalResult(JsonObject message, TokenUsage usage, boolean usedTools) {
        String content = contentOf(message);
        boolean empty = content == null || content.isBlank();
        boolean markup = Fallbacks.looksLikeToolMarkup(content);
        if (usedTools && (empty || markup)) {
            String fallback = Fallbacks.replyJsonOrNull();
            if (fallback != null) {
                AiChatMod.LOGGER.warn("模型没给出可用 JSON（{}），本轮改用兜底话术回复",
                        empty ? "content 为空" : "输出了工具调用标记");
                return new RawResult(fallback, usage);
            }
            AiChatMod.LOGGER.warn("模型没给出可用 JSON（{}），且未配置 fallback_reply，本次按不回复处理",
                    empty ? "content 为空" : "输出了工具调用标记");
        } else if (usedTools) {
            String salvaged = Fallbacks.replyJsonFromProse(content);
            if (salvaged != null) {
                AiChatMod.LOGGER.warn("模型没按 JSON 契约回复（纯文本 {} 字），已把原文当作回复兜底：{}",
                        content.length(), ConsoleSafe.escape(prosePreview(content)));
                return new RawResult(salvaged, usage);
            }
        }
        return new RawResult(content, usage);
    }

    private static String prosePreview(String content) {
        String flat = content.replace("\r", " ").replace("\n", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
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
     * 解析失败一律当没有参数处理，交给工具自己报错。
     */
    private static JsonObject arguments(JsonObject toolCall) {
        JsonElement function = toolCall.get("function");
        if (function == null || !function.isJsonObject()) {
            return new JsonObject();
        }
        JsonElement argsElement = function.getAsJsonObject().get("arguments");
        if (argsElement == null || argsElement.isJsonNull()) {
            return new JsonObject();
        }
        String raw = argsElement.isJsonPrimitive() ? argsElement.getAsString() : argsElement.toString();
        if (raw.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    /**
     * 推理模型的思考内容也计入 max_tokens。预算给小了会得到 finish_reason=length 且 content 为空的响应，
     * 判断环节就会永远"忽略"。这里遇到这种情况自动放大一次预算重试，避免整个模组静默失灵。
     */
    private static CompletableFuture<List<JsonObject>> sendWithRetry(ApiProfile profile, JsonObject body) {
        List<JsonObject> attempts = new ArrayList<>();
        return sendResilient(profile, body).thenCompose(first -> {
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
            return sendResilient(profile, retryBody).thenApply(second -> {
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

    /**
     * 网络瞬时失败（连接超时、握手被掐断、5xx、429）重试一次。
     * 实测中转站偶发这些错误，直接丢弃整条消息体验很差。
     */
    private static CompletableFuture<JsonObject> sendResilient(ApiProfile profile, JsonObject body) {
        return trySend(profile, body, 1);
    }

    private static CompletableFuture<JsonObject> trySend(ApiProfile profile, JsonObject body, int attempt) {
        return send(profile, body).handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }
            Throwable cause = unwrap(error);
            if (attempt >= MAX_NETWORK_ATTEMPTS || !isTransient(cause)) {
                return CompletableFuture.<JsonObject>failedFuture(cause);
            }
            AiChatMod.LOGGER.warn("请求失败（第 {} 次）：{}，{} 秒后重试一次",
                    attempt, ConsoleSafe.escape(String.valueOf(cause.getMessage())), NETWORK_RETRY_DELAY_SECONDS);
            return CompletableFuture.supplyAsync(() -> "",
                            CompletableFuture.delayedExecutor(NETWORK_RETRY_DELAY_SECONDS, TimeUnit.SECONDS))
                    .thenCompose(ignored -> trySend(profile, body, attempt + 1));
        }).thenCompose(future -> future);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 5 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current;
    }

    /** 只重试「重发可能成功」的错误：IO/超时/握手 与 429、5xx；4xx 参数错误重试没意义。 */
    private static boolean isTransient(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && (message.contains("API 返回 429") || message.contains("API 返回 5"))) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static JsonObject baseBody(ApiProfile profile, List<Tool> tools) {
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

        // 部分平台在 json_object 模式下不返回 tool_calls，启用工具时不发送 response_format。
        if (profile.jsonMode() && tools.isEmpty()) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }

        if (!tools.isEmpty()) {
            body.add("tools", toolDefinitions(tools));
        }
        return body;
    }

    /**
     * 把工具注册表翻译成 OpenAI 的 function 格式。工具描述、参数 schema 都由工具自己提供，
     * 新增能力时这里不需要改。
     */
    private static JsonArray toolDefinitions(List<Tool> tools) {
        JsonArray array = new JsonArray();
        for (Tool tool : tools) {
            JsonObject definition = new JsonObject();
            definition.addProperty("type", "function");

            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parameters());

            definition.add("function", function);
            array.add(definition);
        }
        return array;
    }

    private static CompletableFuture<JsonObject> send(ApiProfile profile, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Endpoints.chatCompletions(profile.apiUrl())))
                .timeout(Duration.ofSeconds(Math.max(5, ConfigManager.get().request_timeout_seconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + profile.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        // 登记底层 future：停服时由 InFlight 取消，等于掐掉正在生成的推理
        return InFlight.track(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
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
