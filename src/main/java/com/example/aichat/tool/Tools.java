package com.example.aichat.tool;

import com.example.aichat.AiChatMod;
import com.example.aichat.knowledge.KnowledgeTool;
import com.example.aichat.memory.MemoryReadTool;
import com.example.aichat.memory.MemoryStore;
import com.example.aichat.memory.MemoryWriteTool;
import com.example.aichat.search.SearchTool;
import com.example.aichat.servertools.ServerFilesTool;
import com.example.aichat.servertools.ServerToolAccess;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册表：按配置决定这一轮对话暴露哪些工具，并统一执行。
 * <p>
 * 三组开关互相独立：
 * <ul>
 *   <li>enable_tools —— 知识库检索 + 联网搜索（老行为，默认关闭）；</li>
 *   <li>memory_enabled —— 记忆读写（默认开启，因此会顺带打开工具通道）；</li>
 *   <li>serverTools —— 服务器管理工具，只对「带前缀且有权限的管理员请求」成立，
 *       由 {@link com.example.aichat.servertools.ServerToolAccess} 在消息入口判定。</li>
 * </ul>
 */
public final class Tools {
    private Tools() {
    }

    public static List<Tool> active(boolean knowledgeAndSearch, boolean memory) {
        return active(knowledgeAndSearch, memory, false);
    }

    /**
     * 本轮对话可用的工具，顺序即发给模型的顺序。
     *
     * @param knowledgeAndSearch config.enable_tools
     * @param memory             config.memory_enabled
     * @param serverTools        这条请求是否为有权限的管理员特权请求
     */
    public static List<Tool> active(boolean knowledgeAndSearch, boolean memory, boolean serverTools) {
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
        // 服务器管理工具不看 enable_tools：它有自己的总开关与权限判定，
        // 这里再确认一次总开关，防止「权限判定过了但配置后来被关掉」的窗口期。
        if (serverTools && ServerToolAccess.active()) {
            tools.add(new ServerFilesTool());
        }
        return List.copyOf(tools);
    }

    /**
     * 执行一次工具调用。任何异常都转成文本回灌，让模型照实告诉玩家失败原因，
     * 而不是让整次请求崩掉。
     * <p>
     * 结果还要过两道闸：单个工具自己的上限、以及整次对话的总预算。
     * 预算见底后不再执行新工具，直接让模型基于已有信息作答。
     */
    public static String invoke(List<Tool> tools, String name, JsonObject arguments, ToolRun run) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        for (Tool tool : tools) {
            if (!tool.name().equals(name)) {
                continue;
            }
            if (run != null) {
                run.countCall();
            }
            int remaining = run == null ? Integer.MAX_VALUE : run.remainingBudget();
            if (remaining <= 0) {
                AiChatMod.LOGGER.info("工具 {} 未执行：本次对话工具结果预算（{} KB）已用尽",
                        name, ToolRun.budgetKb());
                return "本次对话的工具结果已达上限（" + ToolRun.budgetKb()
                        + " KB），不再执行新的工具调用。请基于已经拿到的信息直接回答；"
                        + "信息不够就照实说看不到，不要让玩家等。";
            }

            String result;
            try {
                result = tool.invoke(args, run);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                AiChatMod.LOGGER.warn("工具 {} 调用失败：{}", name, ConsoleSafe.escape(message));
                result = "调用失败: " + message;
            }
            int limit = Math.min(tool.maxResultChars(), remaining);
            String content = truncate(result, limit);
            if (run != null) {
                run.consume(content.length());
            }
            if (StateManager.isDebug()) {
                AiChatMod.LOGGER.info("[debug] 工具调用 name={} 结果长度={} 预算剩余={}",
                        name, content.length(), Math.max(0, remaining - content.length()));
            }
            return content;
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
