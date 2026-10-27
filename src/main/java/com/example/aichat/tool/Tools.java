package com.example.aichat.tool;

import com.example.aichat.AiChatMod;
import com.example.aichat.knowledge.KnowledgeTool;
import com.example.aichat.memory.MemoryReadTool;
import com.example.aichat.memory.MemoryStore;
import com.example.aichat.memory.MemoryWriteTool;
import com.example.aichat.search.SearchTool;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册表：按配置决定这一轮对话暴露哪些工具，并统一执行。
 * <p>
 * 两个开关互相独立：
 * <ul>
 *   <li>enable_tools —— 知识库检索 + 联网搜索（老行为，默认关闭）；</li>
 *   <li>memory_enabled —— 记忆读写（默认开启，因此会顺带打开工具通道）。</li>
 * </ul>
 */
public final class Tools {
    private Tools() {
    }

    /**
     * 本轮对话可用的工具，顺序即发给模型的顺序。
     *
     * @param knowledgeAndSearch config.enable_tools
     * @param memory             config.memory_enabled
     */
    public static List<Tool> active(boolean knowledgeAndSearch, boolean memory) {
        List<Tool> tools = new ArrayList<>();
        if (knowledgeAndSearch) {
            tools.add(new KnowledgeTool());
            if (SearchTool.available()) {
                tools.add(new SearchTool());
            }
        }
        if (memory && MemoryStore.enabled()) {
            tools.add(new MemoryReadTool());
            tools.add(new MemoryWriteTool());
        }
        return List.copyOf(tools);
    }

    /**
     * 执行一次工具调用。任何异常都转成文本回灌，让模型照实告诉玩家失败原因，
     * 而不是让整次请求崩掉。
     */
    public static String invoke(List<Tool> tools, String name, JsonObject arguments, ToolRun run) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        for (Tool tool : tools) {
            if (!tool.name().equals(name)) {
                continue;
            }
            String result;
            try {
                result = tool.invoke(args, run);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                AiChatMod.LOGGER.warn("工具 {} 调用失败：{}", name, ConsoleSafe.escape(message));
                result = "调用失败: " + message;
            }
            if (StateManager.isDebug()) {
                AiChatMod.LOGGER.info("[debug] 工具调用 name={} 结果长度={}",
                        name, result == null ? 0 : result.length());
            }
            return truncate(result, tool.maxResultChars());
        }
        AiChatMod.LOGGER.warn("模型调用了未注册的工具：{}", name);
        return "未知工具: " + name + "。只能调用工具列表中已有的工具。";
    }

    private static String truncate(String content, int limit) {
        if (content == null) {
            return "";
        }
        int max = Math.max(1, limit);
        if (content.length() <= max) {
            return content;
        }
        return content.substring(0, max) + "\n…（内容过长，已截断）";
    }
}
