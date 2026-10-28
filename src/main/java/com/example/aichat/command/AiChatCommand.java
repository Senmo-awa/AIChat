package com.example.aichat.command;

import com.example.aichat.config.ConfigManager;
import com.example.aichat.memory.MemoryStore;
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

    private AiChatCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aichat")
                .executes(ctx -> showHelp(ctx.getSource()))
                .then(Commands.literal("token")
                        .requires(ADMIN)
                        .executes(ctx -> showTokens(ctx.getSource()))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearTokens(ctx.getSource()))))
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
        source.sendSuccess(() -> Component.literal("AI Chat 配置已重新加载。"), true);
        return 1;
    }

    private static int clearTokens(CommandSourceStack source) {
        boolean cleared = UsageStore.clear();
        String message = cleared
                ? "token 用量已清空，重新开始统计。"
                : "没有可清空的 token 记录。";
        source.sendSuccess(() -> Component.literal(message), true);
        return cleared ? 1 : 0;
    }

    private static int showHelp(CommandSourceStack source) {
        String text = """
                /aichat on|off|onlyread —— 设置自己的 AI 聊天状态
                管理员命令：
                /aichat token —— 最近 7 天 token 用量
                /aichat token clear —— 清空统计并重新计算
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
        long prompt = 0;
        long completion = 0;
        long total = 0;
        int requests = 0;

        StringBuilder text = new StringBuilder("最近 7 天 token 用量：");
        for (UsageStore.DailyUsage day : days) {
            text.append('\n')
                    .append(day.date())
                    .append("  ")
                    .append(format(day.prompt()))
                    .append(" + ")
                    .append(format(day.completion()))
                    .append(" = ")
                    .append(format(day.total()))
                    .append(" tokens，")
                    .append(day.requests())
                    .append(" 次");
            prompt += day.prompt();
            completion += day.completion();
            total += day.total();
            requests += day.requests();
        }

        if (requests == 0) {
            text.append("\n（暂无记录）");
        } else {
            text.append("\n合计：")
                    .append(format(prompt))
                    .append(" + ")
                    .append(format(completion))
                    .append(" = ")
                    .append(format(total))
                    .append(" tokens，")
                    .append(requests)
                    .append(" 次请求");
        }

        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return requests;
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

    private static String format(long value) {
        return String.format("%,d", value);
    }
}
