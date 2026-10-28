package com.example.aichat.tool;

import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;

/**
 * 一次对话（一次 AiClient.ask）内的工具调用状态。
 * <p>
 * 除了限制重复搜索，还承载「这次请求是谁发起的、有没有服务器管理权限」：
 * 权限判定在消息入口做一次（{@code ServerToolAccess.allowed}），结果随 ToolRun 一路带到工具实现里，
 * 工具执行前再确认一次，避免注册逻辑写漏就变成越权入口。
 * <p>
 * 另外记录「本次对话已经回灌了多少字符」：每一轮工具调用都会把历史结果重新发一遍，
 * 不设总量预算的话，读一次 48KB 日志就能把单次提问推到 7 万 prompt token。
 */
public final class ToolRun {
    private volatile int searches;
    private volatile int resultCharsUsed;
    private final String actorName;
    private final boolean serverToolsAllowed;

    public ToolRun() {
        this("", false);
    }

    public ToolRun(String actorName, boolean serverToolsAllowed) {
        this.actorName = actorName == null ? "" : actorName;
        this.serverToolsAllowed = serverToolsAllowed;
    }

    /** 触发本次对话的玩家名（用于审计日志）。 */
    public String actorName() {
        return actorName;
    }

    /** 这条请求是否来自带前缀且有权限的管理员。 */
    public boolean serverToolsAllowed() {
        return serverToolsAllowed;
    }

    public int searches() {
        return searches;
    }

    public void countSearch() {
        searches++;
    }

    /** 本次对话剩余的工具结果预算（字符）；{@link Integer#MAX_VALUE} 表示不限。 */
    public int remainingBudget() {
        int total = budgetChars();
        if (total <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, total - resultCharsUsed);
    }

    /** 记录已回灌给模型的字符数。 */
    public void consume(int chars) {
        if (chars > 0) {
            resultCharsUsed += chars;
        }
    }

    public int resultCharsUsed() {
        return resultCharsUsed;
    }

    /** config.json 的 tool_result_budget_kb，<=0 表示不限。 */
    public static int budgetChars() {
        ModConfig config = ConfigManager.get();
        if (config == null || config.tool_result_budget_kb <= 0) {
            return 0;
        }
        return config.tool_result_budget_kb * 1024;
    }

    public static int budgetKb() {
        ModConfig config = ConfigManager.get();
        return config == null ? 0 : Math.max(0, config.tool_result_budget_kb);
    }
}
