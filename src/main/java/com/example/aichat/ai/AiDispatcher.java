package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.state.StateManager;
import com.example.aichat.usage.TokenUsage;
import com.example.aichat.usage.UsageStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiDispatcher {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "aichat-worker");
        thread.setDaemon(true);
        return thread;
    });

    // TODO 第一版按规格只维护窗口、不拼进请求；跑通后再把历史上下文加入 messages。
    private static final Deque<ChatContext> RECENT = new ArrayDeque<>();
    private static final AtomicBoolean KEY_WARNED = new AtomicBoolean(false);
    private static volatile MinecraftServer currentServer;

    private AiDispatcher() {
    }

    /**
     * 所有事件进入 AI 之前的统一校验点。
     * 被 ban、全局关闭、玩家 off/onlyread 的消息在这里拦截，不会发往 API。
     */
    public static void submit(ChatContext ctx) {
        if (!StateManager.canReplyTo(ctx.playerName())) {
            return;
        }

        if ("chat".equals(ctx.eventType())) {
            synchronized (RECENT) {
                RECENT.addLast(ctx);
                int max = Math.max(0, ConfigManager.get().max_context_messages);
                while (RECENT.size() > max) {
                    RECENT.removeFirst();
                }
            }
        }

        if (!ConfigManager.isApiKeyConfigured()) {
            if (KEY_WARNED.compareAndSet(false, true)) {
                AiChatMod.LOGGER.warn("未配置 DeepSeek API Key，AI 回复已暂停（请编辑 config/aichat/config.json）");
            }
            return;
        }

        WORKER.submit(() -> {
            try {
                AiResult result = AiClient.ask(ctx).join();
                TokenUsage usage = result.usage();
                if (usage == null) {
                    usage = TokenUsage.ZERO;
                }

                if (usage.total() > 0) {
                    AiChatMod.LOGGER.info(
                            "AI token 用量：prompt={} completion={} total={}（玩家：{}，事件：{}）",
                            usage.prompt(), usage.completion(), usage.total(),
                            ctx.playerName(), ctx.eventType()
                    );
                    UsageStore.record(ctx.playerName(), ConfigManager.get().model, usage);
                }

                if (!result.reply() || result.content() == null) {
                    return;
                }

                MinecraftServer server = currentServer;
                if (server == null) {
                    return;
                }
                String prefix = ConfigManager.get().reply_prefix;
                if (prefix == null) {
                    prefix = "[AI] ";
                }
                String line = prefix + result.content();
                TokenUsage finalUsage = usage;
                server.execute(() -> broadcast(server, line, finalUsage));
            } catch (Exception e) {
                AiChatMod.LOGGER.warn("AI 请求失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 控制台始终能看到 AI 消息；玩家按自己的模式过滤，OFF 玩家收不到。
     * debug 开启时，额外把 token 用量发给在线管理员。
     */
    private static void broadcast(MinecraftServer server, String line, TokenUsage usage) {
        if (!StateManager.isChatEnabled()) {
            return;
        }

        Component message = Component.literal(line);
        server.sendSystemMessage(message);

        boolean debug = StateManager.isDebug();
        Component debugMessage = debug && usage.total() > 0
                ? Component.literal("[AIChat] token: prompt=" + usage.prompt()
                        + " completion=" + usage.completion()
                        + " total=" + usage.total())
                        .withStyle(ChatFormatting.GRAY)
                : null;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (StateManager.canReceiveFromAi(player.getName().getString())) {
                player.sendSystemMessage(message);
            }
            if (debugMessage != null
                    && player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                player.sendSystemMessage(debugMessage);
            }
        }
    }

    public static void setServer(MinecraftServer server) {
        currentServer = server;
    }
}
