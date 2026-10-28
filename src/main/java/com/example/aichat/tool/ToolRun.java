package com.example.aichat.tool;

/**
 * 一次对话（一次 AiClient.ask）内的工具调用状态。
 * <p>
 * 除了限制重复搜索，还承载「这次请求是谁发起的、有没有服务器管理权限」：
 * 权限判定在消息入口做一次（{@code ServerToolAccess.allowed}），结果随 ToolRun 一路带到工具实现里，
 * 工具执行前再确认一次，避免注册逻辑写漏就变成越权入口。
 */
public final class ToolRun {
    private volatile int searches;
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
}
