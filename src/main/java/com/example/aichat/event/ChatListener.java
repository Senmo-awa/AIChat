package com.example.aichat.event;

import com.example.aichat.AiChatMod;
import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.ai.ChatContext;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.servertools.ServerToolAccess;
import com.example.aichat.state.StateManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * 玩家聊天入口。
 * <p>
 * 分两条路：
 * <ul>
 *   <li>带特权前缀（默认 {@code !+}）的消息走 {@code ALLOW_CHAT_MESSAGE}：那里可以拦掉广播，
 *       所以「只发给管理员、不广播」的特权请求必须在这个事件里处理，同时也在这里提交给 AI。</li>
 *   <li>普通消息走 {@code CHAT_MESSAGE}，行为与以前一致；但以 {@code ignored_chat_prefixes}
 *       （默认 {@code !!}，MCDR 的调用前缀）开头的指令类消息在这里被直接丢弃，不送 AI；
 *       发送者名字命中 {@code ignored_player_prefixes}（默认 {@code bot_}）的消息同样丢弃。</li>
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
            // 被忽略的玩家（默认 bot_ 开头的假人）不送 AI，但消息照常广播，行为等同于普通聊天
            if (ConfigManager.isIgnoredPlayer(sender.getName().getString())) {
                if (StateManager.isDebug()) {
                    AiChatMod.LOGGER.info("[debug] 忽略玩家 {} 的特权请求，未提交给 AI：{}",
                            sender.getName().getString(), content);
                }
                return true;
            }
            return handlePrivileged(sender, content);
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
                // 被忽略的玩家（默认 bot_ 开头的假人）说的话不进缓冲、不进历史、不发请求
                String playerName = sender.getName().getString();
                if (ConfigManager.isIgnoredPlayer(playerName)) {
                    if (StateManager.isDebug()) {
                        AiChatMod.LOGGER.info("[debug] 忽略玩家 {} 的消息，未提交给 AI：{}",
                                playerName, content);
                    }
                    return;
                }
                // 指令类消息（默认 !! 开头，MCDR / 指令插件的调用前缀）不是聊天：
                // 不送给 AI、不进缓冲区、不进历史上下文，连请求都不会发。
                if (ConfigManager.isIgnoredChat(content)) {
                    if (StateManager.isDebug()) {
                        AiChatMod.LOGGER.info("[debug] 忽略指令类消息，未提交给 AI：{}", content);
                    }
                    return;
                }

                AiDispatcher.submit(new ChatContext(
                        playerName,
                        "chat",
                        content,
                        System.currentTimeMillis(),
                        false
                ));
            } finally {
                LAST_COMMAND_AT.set(0L);
            }
        });
    }

    /**
     * 带前缀的聊天消息。
     *
     * @return true 表示照常广播；false 表示拦下这条广播（改由模组私发给发送者、管理员和控制台）
     */
    private static boolean handlePrivileged(ServerPlayer sender, String rawContent) {
        String playerName = sender.getName().getString();

        // 没有权限：不发给 AI，也不拦广播（就是一条普通聊天），但要让玩家知道前缀没生效
        if (!ServerToolAccess.allowed(sender)) {
            sender.sendSystemMessage(Component.literal(
                    "你没有权限使用 AI 的服务器管理工具（前缀 "
                            + ServerToolAccess.prefix()
                            + " 仅限管理员）。这条消息没有发送给 AI。"));
            return true;
        }

        ServerToolAccess.PrivilegedRequest request = ServerToolAccess.parse(rawContent);
        if (request.content().isEmpty()) {
            sender.sendSystemMessage(Component.literal(
                    "前缀后面要写上你想让 AI 做的事，例如："
                            + ServerToolAccess.prefix() + " 看一下 mods 目录"));
            return false;
        }

        AiDispatcher.submit(new ChatContext(
                playerName,
                "chat",
                request.content(),
                System.currentTimeMillis(),
                true
        ));

        if (request.broadcast()) {
            return true;
        }
        deliverToAdmins(sender, rawContent);
        return false;
    }

    /**
     * 不广播时的手动投递：控制台 + 发送者 + 有管理权限的在线玩家。
     * 这样"隐藏"不等于"没人看得到"，管理员仍然能在游戏里看到这条请求。
     */
    private static void deliverToAdmins(ServerPlayer sender, String rawContent) {
        MinecraftServer server = sender.level().getServer();
        if (server == null) {
            return;
        }
        Component line = Component.literal("<" + sender.getName().getString() + "> " + rawContent);
        server.sendSystemMessage(line);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == sender
                    || player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                player.sendSystemMessage(line);
            }
        }
    }
}
