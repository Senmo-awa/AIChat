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
