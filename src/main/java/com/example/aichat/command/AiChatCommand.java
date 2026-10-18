package com.example.aichat.command;

import com.example.aichat.config.ConfigManager;
import com.example.aichat.state.PlayerMode;
import com.example.aichat.state.StateManager;
import com.example.aichat.usage.UsageStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.ReloadCommand;
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

    private AiChatCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aichat")
                .executes(ctx -> showHelp(ctx.getSource()))
                .then(Commands.literal("token")
                        .requires(ADMIN)
                        .executes(ctx -> showTokens(ctx.getSource())))
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
                .then(Commands.literal("on")
                        .executes(ctx -> setMode(ctx.getSource(), PlayerMode.ON)))
                .then(Commands.literal("off")
                        .executes(ctx -> setMode(ctx.getSource(), PlayerMode.OFF)))
                .then(Commands.literal("onlyread")
                        .executes(ctx -> setMode(ctx.getSource(), PlayerMode.ONLY_READ)))
        );

        // 覆盖原版 /reload 的执行体：先热重载模组配置，再保留原版数据包重载。
        dispatcher.register(Commands.literal("reload")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> reload(ctx.getSource())));
    }

    private static int reload(CommandSourceStack source) {
        try {
            ConfigManager.reload();
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("AI Chat 配置加载失败：" + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("AI Chat 配置已重新加载。"), true);
        ReloadCommand.reloadPacks(
                source.getServer().getPackRepository().getSelectedIds(),
                source
        );
        return 1;
    }

    private static int showHelp(CommandSourceStack source) {
        String text = """
                /aichat on|off|onlyread —— 设置自己的 AI 聊天状态
                管理员命令：
                /aichat token —— 最近 7 天 token 用量
                /aichat enable|disable —— 全局开启/关闭 AI 聊天
                /aichat debug —— 切换聊天框内的 token 用量显示
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
