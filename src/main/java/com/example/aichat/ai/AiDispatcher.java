package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

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

    public static void submit(ChatContext ctx) {
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
                DeepSeekClient.AiResult result = DeepSeekClient.ask(ctx).join();
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
                server.execute(() ->
                        server.getPlayerList().broadcastSystemMessage(Component.literal(line), false)
                );
            } catch (Exception e) {
                AiChatMod.LOGGER.warn("AI 请求失败: {}", e.getMessage());
            }
        });
    }

    public static void setServer(MinecraftServer server) {
        currentServer = server;
    }
}
