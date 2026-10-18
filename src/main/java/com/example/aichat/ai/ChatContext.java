package com.example.aichat.ai;

import java.util.List;
import java.util.stream.Collectors;

public record ChatContext(
        String playerName,
        String eventType,   // chat / join / quit / batch
        String content,
        long timestamp
) {
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
     */
    public static ChatContext batch(List<ChatContext> events) {
        if (events.size() == 1) {
            return events.get(0);
        }
        String joined = events.stream()
                .map(ChatContext::toUserMessage)
                .collect(Collectors.joining("\n"));
        ChatContext first = events.get(0);
        return new ChatContext(first.playerName(), "batch", joined, first.timestamp());
    }
}
