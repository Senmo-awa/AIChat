package com.example.aichat.ai;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.guard.Guards;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
import com.example.aichat.text.SecretRedactor;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /**
     * 私聊历史：按发起者分开存（key 转小写）。私聊内容不能混进公共历史，
     * A 的私聊也不该出现在 B 的上下文里。最多保留 {@link #PM_MAX_PLAYERS} 位发起者的历史。
     */
    private static final Map<String, Deque<ConversationTurn>> PM_HISTORY =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final int PM_MAX_PLAYERS = 32;
    /** 控制台发起的私聊用这个名字（不是真实玩家，跳过冷却与玩家模式判定）。 */
    public static final String CONSOLE_SENDER = "控制台";
    private static final AtomicBoolean KEY_WARNED = new AtomicBoolean(false);
    /** 收到 stop 后置位：此后不再提交任何事件、不再广播任何消息。 */
    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean(false);
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
        // 服务器正在关闭：玩家会成批被踢下线，quit 事件一条都不该再进 AI
        if (SHUTTING_DOWN.get()) {
            return;
        }
        // 被忽略的玩家（默认 bot_ 开头的假人）：聊天与加入/退出事件一起拦。
        // 放在这里而不是只放在 ChatListener，是为了即便将来多出新的入口也不会漏过滤。
        if (ConfigManager.isIgnoredPlayer(ctx.playerName())) {
            if (StateManager.isDebug()) {
                AiChatMod.LOGGER.info("[debug] 忽略玩家 {} 的事件（{}），未提交给 AI",
                        ctx.playerName(), ctx.eventType());
            }
            return;
        }
        // 指令类消息（默认 !! 开头，MCDR / 指令插件的调用前缀）不送 AI：
        // 即便将来多出新的入口调到这里也不会漏过滤。特权请求不参与这个判定——
        // 它的 !+ 前缀已经被剥掉，内容是管理员明确要让 AI 处理的。
        if (!ctx.serverTools() && ConfigManager.isIgnoredChat(ctx.content())) {
            return;
        }
        // 下面三道节流都只在「聊天」上生效（join/quit 是事件，不该被点名/冷却挡住），
        // 而且都在进缓冲区之前——命中就完全不花 token。
        if (!ctx.serverTools() && "chat".equals(ctx.eventType())) {
            if (!Guards.mentioned(ctx.content())) {
                if (StateManager.isDebug()) {
                    AiChatMod.LOGGER.info("[debug] 没被点名（被动回复），忽略：{}", ctx.content());
                }
                return;
            }
            if (Guards.onCooldown(ctx.playerName())) {
                if (StateManager.isDebug()) {
                    AiChatMod.LOGGER.info("[debug] 玩家冷却中，忽略：{}（间隔 {} 秒）",
                            ctx.playerName(), ConfigManager.get().player_cooldown_seconds);
                }
                return;
            }
        }
        // 每日预算用尽：连 join/quit 一起停，跨天自动恢复（Guards 内每小时最多告警一次）
        if (Guards.overDailyBudget()) {
            return;
        }
        if (!StateManager.canReplyTo(ctx.playerName())) {
            return;
        }

        // 特权请求（管理员用前缀发起的服务器管理请求）不进缓冲区：
        // 缓冲会把同一时间别人的话一起拼进来，那份授权就说不清是谁给的了。
        if (ctx.serverTools()) {
            AiChatMod.LOGGER.info("服务器管理工具请求：{}（特权请求跳过缓冲，立即发送）", ctx.playerName());
            process(List.of(ctx));
            return;
        }

        if (!ConfigManager.get().buffer_enabled) {
            if (StateManager.isDebug()) {
                AiChatMod.LOGGER.info("[debug] 消息缓冲已关闭（buffer_enabled=false），立即发送");
            }
            process(List.of(ctx));
            return;
        }

        List<ChatContext> batch = null;
        int pending;
        int pendingChars;
        synchronized (BUFFER_LOCK) {
            BUFFER.addLast(ctx);
            bufferedChars += ctx.content() == null ? 0 : ctx.content().length();
            if (exceedsLimit()) {
                batch = takeBufferLocked();
                pending = 0;
                pendingChars = 0;
            } else {
                scheduleFlushLocked();
                pending = BUFFER.size();
                pendingChars = bufferedChars;
            }
        }
        if (batch != null) {
            AiChatMod.LOGGER.info("缓冲达到上限（{} 条 / {} 字符），立即发送 {} 条消息",
                    ConfigManager.get().buffer_max_messages, ConfigManager.get().buffer_max_chars,
                    batch.size());
            process(batch);
            return;
        }
        if (StateManager.isDebug()) {
            AiChatMod.LOGGER.info("[debug] 消息进入缓冲：已攒 {} 条 / {} 字符，{} 秒后发送",
                    pending, pendingChars, Math.max(1, ConfigManager.get().buffer_delay_seconds));
        }
    }

    /**
     * 私聊入口（{@code /aichat pm <内容>}）。
     * <p>
     * 与公共聊天有意不同：
     * <ul>
     *   <li>不参与消息缓冲——私聊是「我问一句它答一句」，等了 5 秒才回没意义；</li>
     *   <li>不进公共历史，也不读公共历史：私聊内容不该泄到公屏，也不该被别人的聊天带跑；</li>
     *   <li>回复只发给发起者本人（控制台发起就只打印到控制台）；</li>
     *   <li>token 记到私聊账本，{@code /aichat token} 里单独一行。</li>
     * </ul>
     * 冷却、每日预算、ban、假人过滤仍然生效——私聊不是绕过限流的后门。
     */
    public static void submitPrivate(ChatContext ctx) {
        if (SHUTTING_DOWN.get()) {
            return;
        }
        if (!StateManager.isChatEnabled()) {
            return;
        }
        if (ConfigManager.isIgnoredPlayer(ctx.playerName())) {
            if (StateManager.isDebug()) {
                AiChatMod.LOGGER.info("[debug] 忽略玩家 {} 的私聊请求（前缀过滤）", ctx.playerName());
            }
            return;
        }
        boolean fromConsole = CONSOLE_SENDER.equalsIgnoreCase(ctx.playerName());
        if (!fromConsole) {
            if (Guards.onCooldown(ctx.playerName())) {
                if (StateManager.isDebug()) {
                    AiChatMod.LOGGER.info("[debug] 玩家冷却中，忽略私聊：{}", ctx.playerName());
                }
                return;
            }
            if (!StateManager.canReplyTo(ctx.playerName())) {
                return;
            }
        }
        if (Guards.overDailyBudget()) {
            return;
        }
        if (!ConfigManager.hasUsableApiKey()) {
            if (KEY_WARNED.compareAndSet(false, true)) {
                AiChatMod.LOGGER.warn(
                        "未配置可用的 API Key，AI 回复已暂停（请编辑 config/aichat/config.json 或 system.json）");
            }
            return;
        }

        String key = pmKey(ctx.playerName());
        WORKER.submit(() -> {
            try {
                List<ConversationTurn> history = snapshotPmHistory(key);
                appendPmHistory(key, "user", ctx.toUserMessage());

                AiResult result = AiClient.ask(ctx, history).join();
                TokenUsage usage = result.usage() == null ? TokenUsage.ZERO : result.usage();

                if (SHUTTING_DOWN.get()) {
                    return;
                }

                if (usage.total() > 0) {
                    AiChatMod.LOGGER.info(
                            "AI token 用量（私聊）：prompt={} completion={} total={}（玩家：{}）",
                            usage.prompt(), usage.completion(), usage.total(), ctx.playerName());
                    UsageStore.record(ctx.playerName(), ConfigManager.usageModelName(), usage,
                            UsageStore.CHANNEL_PRIVATE);
                }

                String replyContent = result.content() == null
                        ? null
                        : (ConfigManager.get().redact_secrets
                                ? SecretRedactor.apply(result.content())
                                : result.content());

                if (StateManager.isDebug()) {
                    AiChatMod.LOGGER.info(
                            "[debug] 私聊解析结果：reply={}，content={}（玩家：{}，历史条数：{}）",
                            result.reply(), ConsoleSafe.escape(replyContent),
                            ctx.playerName(), history.size());
                }

                if (!result.reply() || replyContent == null) {
                    return;
                }

                JsonObject historyReply = new JsonObject();
                if (!ConfigManager.isDualModelEnabled()) {
                    historyReply.addProperty("action", "reply");
                }
                historyReply.addProperty("content", replyContent);
                appendPmHistory(key, "assistant", GSON.toJson(historyReply));

                MinecraftServer server = currentServer;
                if (server == null) {
                    return;
                }
                String prefix = ConfigManager.get().reply_prefix;
                if (prefix == null) {
                    prefix = "[AI] ";
                }
                String line = prefix + replyContent;
                TokenUsage finalUsage = usage;
                server.execute(() -> deliverPrivate(server, ctx.playerName(), line, finalUsage));
            } catch (java.util.concurrent.CancellationException e) {
                AiChatMod.LOGGER.info("服务器正在关闭，进行中的私聊推理已中止（玩家：{}）", ctx.playerName());
            } catch (Exception e) {
                AiChatMod.LOGGER.warn("AI 私聊请求失败: {}", ConsoleSafe.escape(e.getMessage()));
            }
        });
    }

    /** 私聊只发给发起者本人；控制台照样留一行日志（控制台就是服主的眼睛）。 */
    private static void deliverPrivate(MinecraftServer server, String targetName, String line,
                                       TokenUsage usage) {
        if (!StateManager.isChatEnabled() || SHUTTING_DOWN.get()) {
            return;
        }
        Component message = Component.literal(line);
        boolean toConsole = CONSOLE_SENDER.equalsIgnoreCase(targetName);
        String consoleLine = toConsole ? "[私聊] " + line : "[私聊→" + targetName + "] " + line;
        server.sendSystemMessage(Component.literal(ConsoleSafe.drop(consoleLine)));

        ServerPlayer target = findPlayer(server, targetName);
        if (target == null) {
            return;
        }
        target.sendSystemMessage(message);
        if (StateManager.isDebug() && usage.total() > 0) {
            target.sendSystemMessage(Component.literal("[AIChat] 私聊 token: prompt=" + usage.prompt()
                    + " completion=" + usage.completion()
                    + " total=" + usage.total()).withStyle(ChatFormatting.GRAY));
        }
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private static String pmKey(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(java.util.Locale.ROOT);
    }

    private static List<ConversationTurn> snapshotPmHistory(String key) {
        synchronized (PM_HISTORY) {
            Deque<ConversationTurn> history = PM_HISTORY.get(key);
            return history == null ? new ArrayList<>() : new ArrayList<>(history);
        }
    }

    private static void appendPmHistory(String key, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        synchronized (PM_HISTORY) {
            Deque<ConversationTurn> history = PM_HISTORY.computeIfAbsent(key, k -> new ArrayDeque<>());
            history.addLast(new ConversationTurn(role, content));
            int max = Math.max(0, ConfigManager.get().max_context_messages);
            while (history.size() > max) {
                history.removeFirst();
            }
            while (PM_HISTORY.size() > PM_MAX_PLAYERS) {
                String eldest = PM_HISTORY.keySet().iterator().next();
                if (eldest.equals(key)) {
                    break;
                }
                PM_HISTORY.remove(eldest);
            }
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
        // 合并了多条才值得记一行（单条的请求本来就有 token 用量那行）；这样「到底有没有合并」一眼可见
        if (batch.size() > 1) {
            AiChatMod.LOGGER.info("缓冲到期：合并 {} 条消息后发送（等待 {} 秒）",
                    batch.size(), Math.max(1, ConfigManager.get().buffer_delay_seconds));
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
        if (events.isEmpty() || !StateManager.isChatEnabled() || SHUTTING_DOWN.get()) {
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

                // 等回复期间服务器可能已经开始关闭（世界保存要几秒）：不再记录、不再广播
                if (SHUTTING_DOWN.get()) {
                    return;
                }

                if (usage.total() > 0) {
                    AiChatMod.LOGGER.info(
                            "AI token 用量：prompt={} completion={} total={}（玩家：{}，事件：{}，消息数：{}）",
                            usage.prompt(), usage.completion(), usage.total(),
                            batch.playerName(), batch.eventType(), events.size()
                    );
                    UsageStore.record(batch.playerName(), ConfigManager.usageModelName(), usage);
                }

                // 出口脱敏：AI 从文件里读到的密码 / Key 不广播出去（默认开）。
                // 连日志也抹——控制台日志常被 MCDR 转到公开频道。
                String replyContent = result.content() == null
                        ? null
                        : (ConfigManager.get().redact_secrets
                                ? SecretRedactor.apply(result.content())
                                : result.content());

                if (StateManager.isDebug()) {
                    AiChatMod.LOGGER.info(
                            "[debug] 解析结果：reply={}，content={}（事件：{}，消息数：{}，历史条数：{}）",
                            result.reply(), ConsoleSafe.escape(replyContent),
                            batch.eventType(), events.size(), history.size());
                }

                if (!result.reply() || replyContent == null) {
                    return;
                }

                // 历史里的 assistant 也保持 JSON 格式，避免模型模仿纯文本而破坏后续解析
                JsonObject historyReply = new JsonObject();
                if (!ConfigManager.isDualModelEnabled()) {
                    historyReply.addProperty("action", "reply");
                }
                historyReply.addProperty("content", replyContent);
                appendHistory("assistant", GSON.toJson(historyReply));

                MinecraftServer server = currentServer;
                if (server == null) {
                    return;
                }
                String prefix = ConfigManager.get().reply_prefix;
                if (prefix == null) {
                    prefix = "[AI] ";
                }
                String line = prefix + replyContent;
                TokenUsage finalUsage = usage;
                server.execute(() -> broadcast(server, line, finalUsage));
            } catch (java.util.concurrent.CancellationException e) {
                // 停服时被主动掐掉的推理，属于预期行为，不算失败
                AiChatMod.LOGGER.info("服务器正在关闭，进行中的 AI 推理已中止（玩家：{}）", batch.playerName());
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
        if (!StateManager.isChatEnabled() || SHUTTING_DOWN.get()) {
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
        if (server != null) {
            // 集成服务器 / 热重载：重新开始接受事件
            SHUTTING_DOWN.set(false);
            InFlight.reset();
        }
    }

    /** 服务器正在关闭：不再接受事件、不再广播。 */
    public static boolean isShuttingDown() {
        return SHUTTING_DOWN.get();
    }

    /** 缓冲区里还没发出去的事件数（/aichat status 用）。 */
    public static int pendingEvents() {
        synchronized (BUFFER_LOCK) {
            return BUFFER.size();
        }
    }

    /** 当前历史上下文条数（/aichat status 用）。 */
    public static int historySize() {
        synchronized (RECENT) {
            return RECENT.size();
        }
    }

    /** 私聊历史里有几位发起者（/aichat status 用）。 */
    public static int privateHistoryPlayers() {
        synchronized (PM_HISTORY) {
            return PM_HISTORY.size();
        }
    }

    /**
     * 收到 stop（{@code ServerLifecycleEvents.SERVER_STOPPING}）时调用：
     * <ol>
     *   <li>置位停服标记——此后聊天、join/quit 事件一律丢弃（停服时每个玩家都会触发一次 quit，
     *       一次 stop 就能白烧十几条请求）；</li>
     *   <li>清空缓冲区、取消待触发的 flush，攒着的话也不会再发；</li>
     *   <li>取消所有在途的 HTTP 请求——世界保存要几秒，这段时间直接掐掉生成中的推理；</li>
     *   <li>断开广播目标，任何迟到的结果都不会再发出去。</li>
     * </ol>
     */
    public static void shutdown() {
        if (!SHUTTING_DOWN.compareAndSet(false, true)) {
            return;
        }
        int dropped;
        synchronized (BUFFER_LOCK) {
            dropped = BUFFER.size();
            BUFFER.clear();
            bufferedChars = 0;
            flushGeneration++;
            if (flushTask != null) {
                flushTask.cancel(false);
                flushTask = null;
            }
        }
        currentServer = null;
        int cancelled = InFlight.cancelAll();
        AiChatMod.LOGGER.info(
                "服务器正在关闭：已停止 AI 请求，清空缓冲区（丢弃 {} 条待处理事件），中止 {} 个在途请求",
                dropped, cancelled);
    }
}
