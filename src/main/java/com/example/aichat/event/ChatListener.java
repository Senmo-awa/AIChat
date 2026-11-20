package com.example.aichat.event;

import com.example.aichat.servertools.ServerToolAccess;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/**
 * Fabric 侧的聊天事件注册。
 * <p>
 * 分两条路：
 * <ul>
 *   <li>带特权前缀（默认 {@code !+}）的消息走 {@code ALLOW_CHAT_MESSAGE}：那里可以拦掉广播，
 *       所以「只发给管理员、不广播」的特权请求必须在这个事件里处理。</li>
 *   <li>普通消息走 {@code CHAT_MESSAGE}，由 {@link ChatHandler#handleChat} 统一判定屏蔽规则。</li>
 * </ul>
 * 特权消息在 CHAT_MESSAGE 里会被直接跳过（前缀判定命中），因此不会重复提交给 AI。
 */
public final class ChatListener {
    /**
     * /say、/me 等命令会在 COMMAND_MESSAGE 之后继续触发 CHAT_MESSAGE。
     * 用时间窗口标记，避免布尔标记在"命令不触发 CHAT_MESSAGE"时残留并吞掉下一条正常聊天。
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

        // 特权请求：先在这里判定权限、决定要不要广播，再提交给 AI。
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String content = message.signedContent();
            if (content == null || content.isBlank() || !ServerToolAccess.matchesPrefix(content)) {
                return true;
            }
            return ChatHandler.handlePrivileged(sender, content) != ChatHandler.Action.SUPPRESS;
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
                // 特权消息已经在 ALLOW_CHAT_MESSAGE 里处理过了（无论是否被拦下）
                if (ServerToolAccess.matchesPrefix(content)) {
                    return;
                }
                ChatHandler.handleChat(sender, content);
            } finally {
                LAST_COMMAND_AT.set(0L);
            }
        });
    }
}
