package com.example.aichat.event;

import com.example.aichat.AiChat;
import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.ai.ChatContext;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.servertools.ServerToolAccess;
import com.example.aichat.state.StateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * 玩家聊天 / 进出事件的统一处理，两个加载器共用。
 * <p>
 * 加载器事件模型不同（Fabric 有 ALLOW_CHAT_MESSAGE 与 CHAT_MESSAGE 两个事件，
 * NeoForge 只有一个可取消的 {@code ServerChatEvent}），所以这里只给出「该怎么处理」的结论，
 * 由各自的入口点决定是照常广播还是取消。
 */
public final class ChatHandler {
    /** 一条聊天消息的处理结论。 */
    public enum Action {
        /** 不归模组管：不提交给 AI，消息照常广播。 */
        PASS,
        /** 已提交给 AI，消息照常广播。 */
        HANDLED,
        /** 已提交给 AI，但这条消息不该广播（改用私发投递给发送者、管理员和控制台）。 */
        SUPPRESS
    }

    private ChatHandler() {
    }

    /**
     * 带特权前缀（默认 {@code !+}）的聊天消息：判定权限、决定广播方式、提交给 AI。
     */
    public static Action handlePrivileged(ServerPlayer sender, String rawContent) {
        String playerName = sender.getName().getString();

        // 命中玩家屏蔽规则：不送 AI，但消息照常广播，行为等同于普通聊天
        if (ConfigManager.isBlockedPlayer(playerName)) {
            if (StateManager.isDebug()) {
                AiChat.LOGGER.info("[debug] {}（玩家 {}，特权请求）：{}",
                        ConfigManager.BLOCK_NOTICE, playerName, rawContent);
            }
            return Action.PASS;
        }

        // 没有权限：不发给 AI，也不拦广播（就是一条普通聊天），但要让玩家知道前缀没生效
        if (!ServerToolAccess.allowed(sender)) {
            sender.sendSystemMessage(Component.literal(
                    "你没有权限使用 AI 的服务器管理工具（前缀 "
                            + ServerToolAccess.prefix()
                            + " 仅限管理员）。这条消息没有发送给 AI。"));
            return Action.PASS;
        }

        ServerToolAccess.PrivilegedRequest request = ServerToolAccess.parse(rawContent);
        if (request.content().isEmpty()) {
            sender.sendSystemMessage(Component.literal(
                    "前缀后面要写上你想让 AI 做的事，例如："
                            + ServerToolAccess.prefix() + " 看一下 mods 目录"));
            return Action.SUPPRESS;
        }

        AiDispatcher.submit(new ChatContext(
                playerName,
                "chat",
                request.content(),
                System.currentTimeMillis(),
                true
        ));

        if (request.broadcast()) {
            return Action.HANDLED;
        }
        deliverToAdmins(sender, rawContent);
        return Action.SUPPRESS;
    }

    /**
     * 普通聊天消息：命中屏蔽规则就不送 AI，否则提交。
     *
     * @return true 表示已提交给 AI，false 表示被屏蔽或无法提交
     */
    public static boolean handleChat(ServerPlayer sender, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String playerName = sender.getName().getString();

        // 命中玩家屏蔽规则：不进缓冲、不进历史、不发请求
        if (ConfigManager.isBlockedPlayer(playerName)) {
            if (StateManager.isDebug()) {
                AiChat.LOGGER.info("[debug] {}（玩家 {}）：{}",
                        ConfigManager.BLOCK_NOTICE, playerName, content);
            }
            return false;
        }
        // 命中消息屏蔽规则（默认 !! 开头的指令调用）：同样不进缓冲、不进历史、不发请求。
        if (ConfigManager.isBlockedMessage(content)) {
            if (StateManager.isDebug()) {
                AiChat.LOGGER.info("[debug] {}（消息规则）：{}",
                        ConfigManager.BLOCK_NOTICE, content);
            }
            return false;
        }

        AiDispatcher.submit(new ChatContext(
                playerName,
                "chat",
                content,
                System.currentTimeMillis(),
                false
        ));
        return true;
    }

    /**
     * 不广播时的手动投递：控制台 + 发送者 + 有管理权限的在线玩家。
     * 这样「隐藏」不等于「没人看得到」，管理员仍然能在游戏里看到这条请求。
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
