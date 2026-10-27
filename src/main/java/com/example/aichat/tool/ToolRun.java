package com.example.aichat.tool;

/**
 * 一次对话（一次 AiClient.ask）内的工具调用状态。
 * <p>
 * 目前只用来限制重复搜索：同一批事件里模型可能反复搜索，把 token 和接口额度烧光。
 * 状态放在这里而不是工具实现里，是因为工具实现是无状态的，按对话计数需要外部承载。
 */
public final class ToolRun {
    private volatile int searches;

    public int searches() {
        return searches;
    }

    public void countSearch() {
        searches++;
    }
}
