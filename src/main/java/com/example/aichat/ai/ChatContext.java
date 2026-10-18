package com.example.aichat.ai;

public record ChatContext(
        String playerName,
        String eventType,   // chat / join / quit
        String content,
        long timestamp
) {
    public String toUserMessage() {
        return "玩家名：" + playerName
                + "｜事件类型：" + eventType
                + "｜时间：" + timestamp
                + "｜内容：" + content;
    }
}
