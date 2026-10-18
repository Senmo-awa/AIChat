package com.example.aichat.event;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.ai.ChatContext;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

public final class ChatListener {
    /**
     * /say、/me 等命令会在 COMMAND_MESSAGE 之后继续触发 CHAT_MESSAGE。
     * 用时间窗口标记，避免布尔标记在“命令不触发 CHAT_MESSAGE”时残留并吞掉下一条正常聊天。
     */
    private static final long COMMAND_WINDOW_NANOS = 50_000_000L;
    private static final ThreadLocal<Long> LAST_COMMAND_AT = ThreadLocal.withInitial(() -> 0L);

    private ChatListener() {
    }

    public static void register() {
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> {
            if (source != null) {
                LAST_COMMAND_AT.set(System.nanoTime());
            }
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            try {
                long lastCommandAt = LAST_COMMAND_AT.get();
                if (lastCommandAt != 0L && System.nanoTime() - lastCommandAt < COMMAND_WINDOW_NANOS) {
                    return;
                }

                String content = message.signedContent();
                if (content == null || content.isBlank()) {
                    return;
                }

                AiDispatcher.submit(new ChatContext(
                        sender.getName().getString(),
                        "chat",
                        content,
                        System.currentTimeMillis()
                ));
            } finally {
                LAST_COMMAND_AT.set(0L);
            }
        });
    }
}
