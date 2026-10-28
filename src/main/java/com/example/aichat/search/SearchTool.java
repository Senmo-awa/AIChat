package com.example.aichat.search;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
import com.example.aichat.tool.Schemas;
import com.example.aichat.tool.Tool;
import com.example.aichat.tool.ToolRun;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * 联网搜索工具（web_search）。搜索由模组执行，与模型平台是否提供服务端搜索无关。
 */
public final class SearchTool implements Tool {
    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索实时信息：新闻、天气、价格、赛事、人物近况等当前事实。"
                + "只有确实需要外部最新信息时才调用，一次对话最多搜一次。"
                + "搜不到就照实说，不要编造。";
    }

    @Override
    public JsonObject parameters() {
        JsonObject properties = new JsonObject();
        properties.add("query", Schemas.stringProperty("搜索关键词，用简洁的短语，不要带整段聊天内容"));
        return Schemas.object(properties, "query");
    }

    /**
     * 只在配置齐全时才注册：宁可让模型看不到它，也不要让玩家看到「搜索总是失败」。
     */
    public static boolean available() {
        ModConfig config = ConfigManager.get();
        if (config == null || !config.search_enabled) {
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

    @Override
    public String invoke(JsonObject arguments, ToolRun run) {
        ModConfig config = ConfigManager.get();
        JsonElement element = arguments.get("query");
        String query = element == null || element.isJsonNull() ? "" : element.getAsString().trim();
        if (query.isEmpty()) {
            return "搜索失败: 缺少 query 参数";
        }
        if (run.searches() > 0) {
            // 防刷：一次对话只允许搜一次，避免模型反复搜索把 token 和接口额度烧光
            return "本次对话已经搜索过一次，请直接基于上面的搜索结果回答，不要重复搜索。";
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
            run.countSearch();
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
}
