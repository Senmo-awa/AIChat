package com.example.aichat.ai;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一条待处理事件。
 *
 * @param serverTools 是否为「带前缀且已通过权限判定的管理员请求」。true 时这一轮对话才会注册
 *                    服务器管理工具，并且不参与消息缓冲（缓冲区可能把别人的话混进来）。
 */
public record ChatContext(
        String playerName,
        String eventType,   // chat / join / quit / batch
        String content,
        long timestamp,
        boolean serverTools
) {
    public ChatContext(String playerName, String eventType, String content, long timestamp) {
        this(playerName, eventType, content, timestamp, false);
    }

    public String toUserMessage() {
        if ("batch".equals(eventType)) {
            return content;
        }
        return "玩家名：" + playerName
                + "｜事件类型：" + eventType
                + "｜时间：" + timestamp
                + "｜内容：" + content;
    }

    /**
     * 发给模型的这一条 user 消息。
     * <p>
     * 特权请求的前缀（{@code !+}）在进 AI 之前就被剥掉了，如果只发 {@link #toUserMessage()}，
     * 模型看到的就是一条普通聊天，只能靠提示词「工具列表里有 server_files 就说明是特权请求」去反推——
     * 实测小模型反推不稳：要么没注意到工具（答“没权限”），要么因为看不到前缀而反过来要玩家加前缀。
     * 所以这里显式标注授权状态，让模型不需要推断。
     * <p>
     * 注意历史里存的是 {@link #toUserMessage()}（不带标注），只有「当前事件」带，避免旧请求的
     * 授权标注留在上下文里误导后续普通聊天。
     */
    public String userMessageForAi() {
        String base = toUserMessage();
        if (!serverTools) {
            return base;
        }
        return "【管理员特权请求】本条消息来自有权限的管理员，特权前缀已由模组剥离，"
                + "server_files 工具已为本次对话启用，可以直接调用；不要再向玩家索要前缀，"
                + "也不要说自己没有权限。\n" + base;
    }

    /**
     * 把缓冲区里的多条事件拼成一条 user 消息；只有一条时保持原样。
     * 特权请求不会进缓冲区，所以拼接结果一定是非特权。
     */
    public static ChatContext batch(List<ChatContext> events) {
        if (events.size() == 1) {
            return events.get(0);
        }
        String joined = events.stream()
                .map(ChatContext::toUserMessage)
                .collect(Collectors.joining("\n"));
        ChatContext first = events.get(0);
        return new ChatContext(first.playerName(), "batch", joined, first.timestamp(), false);
    }
}
