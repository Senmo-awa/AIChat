package com.example.aichat.event;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.ai.ChatContext;
import com.example.aichat.servertools.ServerToolAccess;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;

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
                String playerName = sender.getName().getString();

                // 特权前缀（默认 !+）：只有它开头的消息才可能带上服务器管理工具。
                // 权限在这里判定一次，结果随 ChatContext 一路带下去；没权限的消息直接不进 AI，
                // 免得既浪费 token 又让玩家以为 AI 在无视自己。
                boolean serverTools = false;
                if (ServerToolAccess.matchesPrefix(content)) {
                    if (!ServerToolAccess.allowed(sender)) {
                        sender.sendSystemMessage(Component.literal(
                                "你没有权限使用 AI 的服务器管理工具（前缀 "
                                        + ServerToolAccess.prefix()
                                        + " 仅限管理员）。这条消息没有发送给 AI。"));
                        return;
                    }
                    content = ServerToolAccess.stripPrefix(content);
                    if (content.isEmpty()) {
                        sender.sendSystemMessage(Component.literal(
                                "前缀后面要写上你想让 AI 做的事，例如："
                                        + ServerToolAccess.prefix() + " 看一下 mods 目录"));
                        return;
                    }
                    serverTools = true;
                }

                AiDispatcher.submit(new ChatContext(
                        playerName,
                        "chat",
                        content,
                        System.currentTimeMillis(),
                        serverTools
                ));
            } finally {
                LAST_COMMAND_AT.set(0L);
            }
        });
    }
}
