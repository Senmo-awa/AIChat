package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
import com.example.aichat.usage.TokenUsage;
import com.example.aichat.usage.UsageStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiDispatcher {
    private static final Gson GSON = new Gson();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "aichat-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "aichat-buffer");
        thread.setDaemon(true);
        return thread;
    });

    // 最近对话（玩家事件 + AI 回复），请求时作为历史上下文一起发送。
    private static final Deque<ConversationTurn> RECENT = new ArrayDeque<>();
    private static final AtomicBoolean KEY_WARNED = new AtomicBoolean(false);
    private static volatile MinecraftServer currentServer;

    // 消息缓冲：最后一条消息后等待若干秒，期间的新消息会重置计时器并合并发送。
    private static final Object BUFFER_LOCK = new Object();
    private static final Deque<ChatContext> BUFFER = new ArrayDeque<>();
    private static int bufferedChars = 0;
    private static long flushGeneration = 0;
    private static ScheduledFuture<?> flushTask;

    private AiDispatcher() {
    }

    /**
     * 所有事件进入 AI 之前的统一校验点。
     * 被 ban、全局关闭、玩家 off/onlyread 的消息在这里拦截，不会进入缓冲区。
     */
    public static void submit(ChatContext ctx) {
        if (!StateManager.canReplyTo(ctx.playerName())) {
            return;
        }

        if (!ConfigManager.get().buffer_enabled) {
            process(List.of(ctx));
            return;
        }

        List<ChatContext> batch = null;
        synchronized (BUFFER_LOCK) {
            BUFFER.addLast(ctx);
            bufferedChars += ctx.content() == null ? 0 : ctx.content().length();
            if (exceedsLimit()) {
                batch = takeBufferLocked();
            } else {
                scheduleFlushLocked();
            }
        }
        if (batch != null) {
            process(batch);
        }
    }

    private static boolean exceedsLimit() {
        return BUFFER.size() >= Math.max(1, ConfigManager.get().buffer_max_messages)
                || bufferedChars >= Math.max(1, ConfigManager.get().buffer_max_chars);
    }

    private static void scheduleFlushLocked() {
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        long generation = ++flushGeneration;
        int delay = Math.max(1, ConfigManager.get().buffer_delay_seconds);
        flushTask = TIMER.schedule(() -> flushDue(generation), delay, TimeUnit.SECONDS);
    }

    private static void flushDue(long generation) {
        List<ChatContext> batch = null;
        synchronized (BUFFER_LOCK) {
            if (generation != flushGeneration || BUFFER.isEmpty()) {
                return;
            }
            batch = takeBufferLocked();
        }
        process(batch);
    }

    private static List<ChatContext> takeBufferLocked() {
        List<ChatContext> batch = new ArrayList<>(BUFFER);
        BUFFER.clear();
        bufferedChars = 0;
        flushGeneration++;
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        return batch;
    }

    private static void process(List<ChatContext> events) {
        if (events.isEmpty() || !StateManager.isChatEnabled()) {
            return;
        }

        ChatContext batch = ChatContext.batch(events);

        if (!ConfigManager.hasUsableApiKey()) {
            if (KEY_WARNED.compareAndSet(false, true)) {
                AiChatMod.LOGGER.warn(
                        "未配置可用的 API Key，AI 回复已暂停（请编辑 config/aichat/config.json 或 system.json）");
            }
            return;
        }

        WORKER.submit(() -> {
            try {
                // 在 worker 内串行维护历史，保证多批次排队时顺序正确
                List<ConversationTurn> history = snapshotHistory();
                appendHistory("user", batch.toUserMessage());

                AiResult result = AiClient.ask(batch, history).join();
                TokenUsage usage = result.usage() == null ? TokenUsage.ZERO : result.usage();

                if (usage.total() > 0) {
                    AiChatMod.LOGGER.info(
                            "AI token 用量：prompt={} completion={} total={}（玩家：{}，事件：{}，消息数：{}）",
                            usage.prompt(), usage.completion(), usage.total(),
                            batch.playerName(), batch.eventType(), events.size()
                    );
                    UsageStore.record(batch.playerName(), ConfigManager.usageModelName(), usage);
                }

                if (StateManager.isDebug()) {
                    AiChatMod.LOGGER.info(
                            "[debug] 解析结果：reply={}，content={}（事件：{}，消息数：{}，历史条数：{}）",
                            result.reply(), ConsoleSafe.escape(result.content()),
                            batch.eventType(), events.size(), history.size());
                }

                if (!result.reply() || result.content() == null) {
                    return;
                }

                // 历史里的 assistant 也保持 JSON 格式，避免模型模仿纯文本而破坏后续解析
                JsonObject historyReply = new JsonObject();
                if (!ConfigManager.isDualModelEnabled()) {
                    historyReply.addProperty("action", "reply");
                }
                historyReply.addProperty("content", result.content());
                appendHistory("assistant", GSON.toJson(historyReply));

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
                AiChatMod.LOGGER.warn("AI 请求失败: {}", ConsoleSafe.escape(e.getMessage()));
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

        // 控制台那份单独过滤：中文 Windows 的 GBK 控制台写不了颜文字里的 ｡ ・ ∀ ๑ 之类，
        // 会让 MCDR 等包装器的日志写入抛 UnicodeEncodeError。玩家走网络 UTF-8，不受影响。
        Component message = Component.literal(line);
        Component consoleMessage = Component.literal(ConsoleSafe.drop(line));
        server.sendSystemMessage(consoleMessage);

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

    private static List<ConversationTurn> snapshotHistory() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    private static void appendHistory(String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        synchronized (RECENT) {
            RECENT.addLast(new ConversationTurn(role, content));
            int max = Math.max(0, ConfigManager.get().max_context_messages);
            while (RECENT.size() > max) {
                RECENT.removeFirst();
            }
        }
    }

    public static void setServer(MinecraftServer server) {
        currentServer = server;
    }
}
