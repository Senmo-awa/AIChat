package com.example.aichat.command;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.ai.ChatContext;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.example.aichat.guard.Guards;
import com.example.aichat.memory.MemoryStore;
import com.example.aichat.model.ModelRegistry;
import com.example.aichat.model.ModelSpec;
import com.example.aichat.servertools.ServerToolAccess;
import com.example.aichat.state.PlayerMode;
import com.example.aichat.state.StateManager;
import com.example.aichat.usage.UsageStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * /aichat 命令树。
 * 管理员命令：token / enable / disable / debug / ban / unban / banlist
 * 玩家命令：on / off / onlyread
 */
public final class AiChatCommand {
    private static final Predicate<CommandSourceStack> ADMIN =
            Commands.hasPermission(Commands.LEVEL_MODERATORS);
    /** 改管理员名单等于发服务器管理权限，要求 op 满级（4 级）。 */
    private static final Predicate<CommandSourceStack> OWNER =
            Commands.hasPermission(Commands.LEVEL_OWNERS);
    /** 单条私聊内容上限：既是防刷屏，也防一次把上下文撑爆。 */
    private static final int MAX_PRIVATE_CHARS = 1000;

    private AiChatCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aichat")
                .executes(ctx -> showHelp(ctx.getSource()))
                .then(Commands.literal("token")
                        .requires(ADMIN)
                        .executes(ctx -> showTokens(ctx.getSource()))
                        .then(Commands.literal("pm")
                                .executes(ctx -> showPrivateTokens(ctx.getSource())))
                        .then(Commands.literal("model")
                                .executes(ctx -> showModelTokens(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearTokens(ctx.getSource(), false))
                                .then(Commands.literal("all")
                                        .executes(ctx -> clearTokens(ctx.getSource(), true)))))
                .then(Commands.literal("pm")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> privateChat(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("enable")
                        .requires(ADMIN)
                        .executes(ctx -> setChatEnabled(ctx.getSource(), true)))
                .then(Commands.literal("disable")
                        .requires(ADMIN)
                        .executes(ctx -> setChatEnabled(ctx.getSource(), false)))
                .then(Commands.literal("debug")
                        .requires(ADMIN)
                        .executes(ctx -> toggleDebug(ctx.getSource())))
                .then(Commands.literal("ban")
                        .requires(ADMIN)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> ban(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("unban")
                        .requires(ADMIN)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> unban(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("banlist")
                        .requires(ADMIN)
                        .executes(ctx -> showBanList(ctx.getSource())))
                .then(Commands.literal("memory")
                        .requires(ADMIN)
                        .executes(ctx -> showMemory(ctx.getSource())))
                .then(Commands.literal("on")
                        .executes(ctx -> setMode(ctx.getSource(), PlayerMode.ON)))
                .then(Commands.literal("off")
                        .executes(ctx -> setMode(ctx.getSource(), PlayerMode.OFF)))
                .then(Commands.literal("onlyread")
                        .executes(ctx -> setMode(ctx.getSource(), PlayerMode.ONLY_READ)))
                .then(Commands.literal("tools")
                        .requires(ADMIN)
                        .executes(ctx -> showTools(ctx.getSource()))
                        .then(Commands.literal("list")
                                .executes(ctx -> showTools(ctx.getSource())))
                        .then(Commands.literal("add")
                                .requires(OWNER)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> addToolAdmin(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("remove")
                                .requires(OWNER)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> removeToolAdmin(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("status")
                        .requires(ADMIN)
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("models")
                        .requires(ADMIN)
                        .executes(ctx -> showModels(ctx.getSource())))
                .then(Commands.literal("reload")
                        .requires(ADMIN)
                        .executes(ctx -> reload(ctx.getSource())))
        );
    }

    private static int reload(CommandSourceStack source) {
        try {
            ConfigManager.reload();
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("AI Chat 配置加载失败：" + e.getMessage()));
            return 0;
        }
        // 配置换了，旧的冷却计时不该继续压着玩家
        Guards.resetCooldowns();
        source.sendSuccess(() -> Component.literal("AI Chat 配置已重新加载。"), true);
        return 1;
    }

    /**
     * /aichat status：一屏看清「现在到底什么在生效」。
     * 排查「配置写了没生效」比发消息试探快，也顺手能看到今日花了多少 token。
     */
    private static int showStatus(CommandSourceStack source) {
        ModConfig config = ConfigManager.get();
        if (config == null) {
            source.sendFailure(Component.literal("AI Chat 配置未加载。"));
            return 0;
        }
        long today = UsageStore.todayTotal();
        long remainValue = config.daily_token_budget <= 0
                ? 0L
                : Math.max(0L, config.daily_token_budget - today);
        int requests = UsageStore.lastDays(1).stream().mapToInt(UsageStore.DailyUsage::requests).sum();
        String cooldown = config.player_cooldown_seconds <= 0
                ? "关"
                : config.player_cooldown_seconds + " 秒";
        String mention = config.reply_only_when_mentioned
                ? "开（点名：" + config.mention_names + "）"
                : "关";
        String prefixes = config.ignored_chat_prefixes == null || config.ignored_chat_prefixes.isEmpty()
                ? "[]"
                : config.ignored_chat_prefixes.toString();
        String ignoredPlayers = config.ignored_player_prefixes == null || config.ignored_player_prefixes.isEmpty()
                ? "[]"
                : config.ignored_player_prefixes.toString();

        source.sendSuccess(() -> Component.literal("—— AI Chat 状态 ——"), false);
        source.sendSuccess(() -> Component.literal(
                "回复：" + (StateManager.isChatEnabled() ? "开" : "关")
                        + "｜debug：" + (StateManager.isDebug() ? "开" : "关")
                        + "｜历史：" + AiDispatcher.historySize() + " 条"
                        + "｜缓冲待发：" + AiDispatcher.pendingEvents() + " 条"), false);
        source.sendSuccess(() -> Component.literal(
                "模型：" + config.model
                        + "｜协议：" + config.api_type
                        + "｜模式：" + modelModeLabel()
                        + "｜工具：" + (config.enable_tools ? "开" : "关")), false);
        source.sendSuccess(() -> TokenReport.statusLine(
                today, config.daily_token_budget, remainValue, requests), false);
        source.sendSuccess(() -> Component.literal(
                "冷却：" + cooldown
                        + "｜被动回复：" + mention
                        + "｜密钥脱敏：" + (config.redact_secrets ? "开" : "关")), false);
        source.sendSuccess(() -> Component.literal(
                "私聊：" + ConfigManager.describePrivateChatMode()
                        + "｜私聊历史：" + AiDispatcher.privateHistoryPlayers() + " 位发起者"), false);
        source.sendSuccess(() -> Component.literal(
                "忽略前缀：" + prefixes
                        + "｜忽略玩家：" + ignoredPlayers), false);
        return 1;
    }

    /**
     * 清空 token 统计。
     * <p>
     * 默认只清公屏账本，私聊消耗不动（私聊常常是服主自己的测试记录，删了就没法对账）；
     * 要连私聊一起清得显式写 {@code /aichat token clear all}。
     */
    private static int clearTokens(CommandSourceStack source, boolean all) {
        boolean cleared = UsageStore.clear(all);
        String message;
        if (cleared) {
            message = all
                    ? "已清空全部 token 记录（公屏 + 私聊），重新开始统计。"
                    : "已清空公屏 token 记录，私聊记录保留（要连私聊一起清：/aichat token clear all）。";
        } else {
            message = all
                    ? "没有可清空的 token 记录。"
                    : "没有可清空的公屏 token 记录（私聊记录不计入这里）。";
        }
        source.sendSuccess(() -> Component.literal(message), true);
        return cleared ? 1 : 0;
    }

    /** /aichat token pm：私聊账本的按玩家明细，方便对账与排查「谁在私聊烧 token」。 */
    private static int showPrivateTokens(CommandSourceStack source) {
        List<UsageStore.PlayerUsage> players = UsageStore.privateByPlayer(7);
        sendLines(source, TokenReport.privateByPlayer(players));
        return 1;
    }

    /** /aichat models：多模型编排一览（角色、模型名、目录、路由、Key/接口是否继承主配置）。 */
    private static int showModels(CommandSourceStack source) {
        if (!ConfigManager.isMultiModelEnabled()) {
            source.sendSuccess(() -> Component.literal(
                    "多模型未启用（system.json 里把 multi_model_enabled 设为 true 后 /aichat reload）。当前是"
                            + modelModeLabel() + "。每个模型的配置在 config/aichat/models/<id>/model.json。"), false);
            return 0;
        }
        List<ModelSpec> models = ModelRegistry.all();
        StringBuilder text = new StringBuilder("多模型（" + models.size() + " 个，AI 互相对话最多 "
                + ConfigManager.aiDialogueMaxRounds() + " 轮）：");
        String mainKey = ConfigManager.get().api_key;
        String mainUrl = ConfigManager.get().api_url;
        for (ModelSpec spec : models) {
            text.append("\n - ").append(spec.id())
                    .append(" [").append(spec.role().label()).append("]  ")
                    .append(spec.model())
                    .append("｜Key：").append(spec.apiKey() != null && spec.apiKey().equals(mainKey) ? "继承" : "独立")
                    .append("｜接口：").append(spec.apiUrl() != null && spec.apiUrl().equals(mainUrl) ? "继承" : "独立")
                    .append("｜目录：").append(relativeDir(spec.dir()));
            if (spec.role() == ModelSpec.Role.JUDGE) {
                text.append("｜路由：")
                        .append(spec.routesTo().isEmpty() ? "全部 chat" : spec.routesTo().toString());
            } else if (spec.role() == ModelSpec.Role.CHAT) {
                text.append(spec.replyToAi() ? "｜参与 AI 对话" : "｜不参与 AI 对话");
            }
        }
        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return models.size();
    }

    private static String relativeDir(java.nio.file.Path dir) {
        try {
            return ConfigManager.dir().relativize(dir).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return dir.toString();
        }
    }

    /** /aichat token model：多模型模式下按模型看谁在花钱。 */
    private static int showModelTokens(CommandSourceStack source) {
        List<UsageStore.ModelUsage> models = UsageStore.byModel(7);
        sendLines(source, TokenReport.byModel(models));
        return Math.max(1, models.size());
    }

    /**
     * /aichat pm &lt;内容&gt;：私聊问 AI 一句，回复只发给自己。
     * <p>
     * 玩家与控制台都能用——控制台调这个命令就是最省事的「私聊通道自检」：
     * 不打扰任何玩家，回复直接打在服务端控制台里。
     * 可用范围由 config.json 的 {@code private_chat_mode} 决定（off / admin / all）。
     */
    private static int privateChat(CommandSourceStack source, String message) {
        String mode = ConfigManager.privateChatMode();
        if ("off".equals(mode)) {
            source.sendFailure(Component.literal(
                    "私聊功能已关闭（config.json 的 private_chat_mode 设为 admin 或 all 后 /aichat reload）。"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        String sender;
        boolean admin;
        if (player == null) {
            sender = AiDispatcher.CONSOLE_SENDER;
            admin = true;
        } else {
            sender = player.getName().getString();
            admin = ServerToolAccess.isModerator(player);
            if ("admin".equals(mode) && !admin) {
                source.sendFailure(Component.literal(
                        "私聊目前仅管理员可用（private_chat_mode=admin）。管理员判定沿用 server_tools_permission_mode。"));
                return 0;
            }
        }
        String content = message == null ? "" : message.trim();
        if (content.isEmpty()) {
            source.sendFailure(Component.literal("用法：/aichat pm <要对 AI 说的话>"));
            return 0;
        }
        int limit = MAX_PRIVATE_CHARS;
        if (content.length() > limit) {
            source.sendFailure(Component.literal("私聊内容太长（" + content.length() + " 字），上限 " + limit + " 字。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("你对小墨私聊说：" + content), false);
        // debug 模式下管理员可以在私聊里使用服务器管理工具：这是一条不惊动公屏的排查通道
        // （普通玩家即使开着 debug 也拿不到工具，admin 判定与私聊可用范围各自独立）。
        boolean tools = StateManager.isDebug() && admin
                && ServerToolAccess.active();
        AiDispatcher.submitPrivate(new ChatContext(
                sender, "pm", content, System.currentTimeMillis(), tools, true));
        return 1;
    }

    private static int showHelp(CommandSourceStack source) {
        String text = """
                /aichat on|off|onlyread —— 设置自己的 AI 聊天状态
                /aichat pm <内容> —— 私聊问 AI 一句，回复只有你自己看得到（可用范围见 private_chat_mode）
                管理员命令：
                /aichat token —— 最近 7 天 token 用量（公屏与私聊分开统计）
                /aichat token pm —— 私聊 token 按玩家明细
                /aichat token model —— 最近 7 天按模型分类的 token（多模型）
                /aichat token clear —— 清空公屏统计（私聊记录保留）
                /aichat token clear all —— 连私聊一起清空
                /aichat models —— 多模型编排状态（角色 / 模型名 / 目录 / 路由）
                /aichat reload —— 热重载配置文件
                /aichat enable|disable —— 全局开启/关闭 AI 聊天
                /aichat debug —— 切换调试模式（聊天框 token 用量 + 日志原始响应）
                /aichat memory —— 查看记忆文件夹（开关见 config.json 的 memory_enabled）
                /aichat tools —— 服务器管理工具状态与管理员名单
                /aichat tools add|remove <玩家> —— 维护名单（仅 op 4 级可用，list 模式生效）
                /aichat ban <玩家> —— 不再回复该玩家（unban 解除，banlist 查看）""";
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int showTokens(CommandSourceStack source) {
        List<UsageStore.DailyUsage> days = UsageStore.lastDays(7);
        int requests = 0;
        for (UsageStore.DailyUsage day : days) {
            requests += day.requests();
        }

        List<Component> lines = new ArrayList<>(TokenReport.daily(days));

        // 私聊单独一块：不与公屏混算，清空也是分开的
        long pmTotal = 0;
        int pmRequests = 0;
        List<UsageStore.PlayerUsage> pmPlayers = UsageStore.privateByPlayer(7);
        for (UsageStore.PlayerUsage player : pmPlayers) {
            pmTotal += player.total();
            pmRequests += player.requests();
        }
        lines.add(TokenReport.privateSummary(pmTotal, pmRequests, pmPlayers.size()));

        sendLines(source, lines);
        return requests;
    }

    /** 表格按行发：一行一条聊天消息，换行不依赖组件里塞 {@code \n}。 */
    private static void sendLines(CommandSourceStack source, List<Component> lines) {
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
    }

    private static int setChatEnabled(CommandSourceStack source, boolean enabled) {
        StateManager.setChatEnabled(enabled);
        String message = enabled ? "AI 聊天已开启。" : "AI 聊天已关闭，所有玩家消息不再发送给 AI。";
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int toggleDebug(CommandSourceStack source) {
        boolean enabled = StateManager.toggleDebug();
        String message = "AI token 调试显示已" + (enabled ? "开启。" : "关闭。");
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int ban(CommandSourceStack source, String playerName) {
        if (StateManager.ban(playerName)) {
            source.sendSuccess(
                    () -> Component.literal("已禁止 " + playerName + " 触发 AI 回复。"),
                    true
            );
        } else {
            source.sendFailure(Component.literal(playerName + " 已在禁止列表中。"));
        }
        return 1;
    }

    private static int unban(CommandSourceStack source, String playerName) {
        if (StateManager.unban(playerName)) {
            source.sendSuccess(
                    () -> Component.literal("已解除对 " + playerName + " 的禁止。"),
                    true
            );
        } else {
            source.sendFailure(Component.literal(playerName + " 不在禁止列表中。"));
        }
        return 1;
    }

    private static int showBanList(CommandSourceStack source) {
        List<String> banned = StateManager.bannedList();
        if (banned.isEmpty()) {
            source.sendSuccess(() -> Component.literal("禁止列表为空。"), false);
            return 0;
        }
        StringBuilder text = new StringBuilder("禁止列表（").append(banned.size()).append("）：");
        for (String name : banned) {
            text.append("\n - ").append(name);
        }
        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return banned.size();
    }

    /** 服务器管理工具的状态可视：开关、前缀、权限方式、名单，以及“我自己能不能用”。 */
    private static int showTools(CommandSourceStack source) {
        List<String> admins = ServerToolAccess.admins();
        StringBuilder text = new StringBuilder("服务器管理工具：");
        text.append(ServerToolAccess.enabled()
                ? "已开启"
                : "已关闭（config.json 里把 server_tools_enabled 设为 true 后 /aichat reload）");
        text.append("\n特权前缀：");
        if (ServerToolAccess.prefix().isEmpty()) {
            text.append("（空，前缀通道关闭）");
        } else {
            text.append('"').append(ServerToolAccess.prefix())
                    .append("\"（玩家在聊天框里以它开头才会调用工具）");
        }
        text.append("\n权限方式：").append(ServerToolAccess.describePermission());
        text.append("\n消息广播：").append(ServerToolAccess.describeBroadcast());
        text.append("\n管理员名单（list 模式生效，共 ").append(admins.size()).append(" 人）：");
        if (admins.isEmpty()) {
            text.append("（空）");
        } else {
            for (String admin : admins) {
                text.append("\n - ").append(admin);
            }
        }
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            text.append("\n你的权限：")
                    .append(ServerToolAccess.allowed(player) ? "可以调用" : "不能调用");
        }
        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int addToolAdmin(CommandSourceStack source, String playerName) {
        if (!"list".equals(ServerToolAccess.permissionMode())) {
            source.sendSuccess(() -> Component.literal(
                    "当前权限方式是 " + ServerToolAccess.permissionMode()
                            + "，名单文件不生效。把 config.json 的 server_tools_permission_mode 改成 \"list\" 再 /aichat reload。"),
                    false);
        }
        if (ServerToolAccess.addAdmin(playerName)) {
            source.sendSuccess(() -> Component.literal("已把 " + playerName + " 加入服务器管理工具名单。"), true);
        } else {
            source.sendFailure(Component.literal(playerName + " 已在名单里。"));
        }
        return 1;
    }

    private static int removeToolAdmin(CommandSourceStack source, String playerName) {
        if (ServerToolAccess.removeAdmin(playerName)) {
            source.sendSuccess(() -> Component.literal("已把 " + playerName + " 移出服务器管理工具名单。"), true);
        } else {
            source.sendFailure(Component.literal(playerName + " 不在名单里。"));
        }
        return 1;
    }

    private static int showMemory(CommandSourceStack source) {
        if (!MemoryStore.enabled()) {
            source.sendSuccess(() -> Component.literal(
                    "记忆功能当前关闭（config.json 里把 memory_enabled 设为 true 后执行 /aichat reload）。"), false);
            return 0;
        }
        List<MemoryStore.Entry> entries = MemoryStore.entries();
        StringBuilder text = new StringBuilder()
                .append("记忆目录：").append(MemoryStore.dirName())
                .append("/（单文件上限 ").append(MemoryStore.limitKb()).append(" KB）");
        if (entries.isEmpty()) {
            text.append("\n（暂无记忆文件）");
        } else {
            text.append("\n共 ").append(entries.size()).append(" 个文件：");
            for (MemoryStore.Entry entry : entries) {
                text.append("\n - ").append(entry.name()).append("  ").append(entry.humanSize());
            }
        }
        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return entries.size();
    }

    private static int setMode(CommandSourceStack source, PlayerMode mode)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        StateManager.setMode(player.getName().getString(), mode);
        String message = "你的 AI 聊天状态：" + mode.label()
                + "（" + switch (mode) {
                    case ON -> "会收到并回应消息";
                    case OFF -> "看不到 AI 消息，AI 也不会回应你";
                    case ONLY_READ -> "能看到 AI 消息，但 AI 不会回应你";
                } + "）";
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /** 当前模式的一句话描述：单模型 / 双模型（旧）/ 多模型 N 个。 */
    private static String modelModeLabel() {
        if (ConfigManager.isMultiModelEnabled()) {
            return "多模型 " + ModelRegistry.all().size() + " 个";
        }
        return ConfigManager.isDualModelEnabled() ? "双模型（旧）" : "单模型";
    }
}
