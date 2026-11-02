package com.example.aichat.guard;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.example.aichat.usage.UsageStore;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 入口节流：玩家冷却、每日 token 预算、被动回复（只在被点名时说话）。
 * <p>
 * 三者的共同点是「在花 token 之前就判掉」——判定放在 {@code AiDispatcher.submit()} 最前面，
 * 命中即返回：不进缓冲区、不进历史上下文、不发 HTTP 请求。
 * <p>
 * 门槛全部默认关闭（{@code player_cooldown_seconds=0}、{@code daily_token_budget=0}、
 * {@code reply_only_when_mentioned=false}），不配就是原来的行为。
 */
public final class Guards {
    /** 每个玩家上一次被接受的消息时间（毫秒）。key 已转小写。 */
    private static final Map<String, Long> LAST_ACCEPTED = new ConcurrentHashMap<>();
    /** 预算告警的最小间隔：用尽后每小时最多喊一次，别刷屏。 */
    private static final long BUDGET_WARN_INTERVAL_MS = 60L * 60L * 1000L;
    private static volatile long budgetWarnedAt;

    private Guards() {
    }

    /**
     * 该玩家是否还在冷却里。没在冷却就顺手更新计时（所以只对「真的被接受」的消息调用一次）。
     *
     * @return true = 这条消息该被丢掉
     */
    public static boolean onCooldown(String playerName) {
        ModConfig config = ConfigManager.get();
        if (config == null || config.player_cooldown_seconds <= 0) {
            return false;
        }
        String key = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = LAST_ACCEPTED.get(key);
        if (last != null && now - last < config.player_cooldown_seconds * 1000L) {
            return true;
        }
        LAST_ACCEPTED.put(key, now);
        return false;
    }

    /**
     * 今日 token 是否已用尽。用尽时暂停一切 AI 请求（聊天、join/quit 都停），跨天自动恢复。
     * <p>
     * 预算判断看的是 {@code usage.jsonl} 的今日累计，每 30 秒最多重算一次（见
     * {@link UsageStore#todayTotal()}），不会每条聊天都读一遍文件。
     *
     * @return true = 该丢掉这条请求
     */
    public static boolean overDailyBudget() {
        ModConfig config = ConfigManager.get();
        if (config == null || config.daily_token_budget <= 0) {
            return false;
        }
        long used = UsageStore.todayTotal();
        if (used < config.daily_token_budget) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - budgetWarnedAt >= BUDGET_WARN_INTERVAL_MS) {
            budgetWarnedAt = now;
            AiChatMod.LOGGER.warn(
                    "今日 token 已用 {} / 预算 {}，暂停 AI 请求"
                            + "（跨天自动恢复；调高 daily_token_budget 或 /aichat token clear 可提前解除）",
                    used, config.daily_token_budget);
        }
        return true;
    }

    /**
     * 是否满足「被动回复」的触发条件。
     * <p>
     * 没开启 {@code reply_only_when_mentioned} 时恒为 true（旧行为：所有消息都处理）。
     * 开启后，内容里必须出现 {@code mention_names} 中的任意一个词（大小写不敏感、允许带 @）。
     * 点名词列表为空属于配置异常，此时不误杀，按「不启用过滤」处理。
     */
    public static boolean mentioned(String content) {
        ModConfig config = ConfigManager.get();
        if (config == null || !config.reply_only_when_mentioned) {
            return true;
        }
        List<String> names = config.mention_names;
        if (names == null || names.isEmpty()) {
            return true;
        }
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name != null && !name.isBlank()
                    && lower.contains(name.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 清空冷却计时（重新加载配置后避免旧计时继续压着玩家）。 */
    public static void resetCooldowns() {
        LAST_ACCEPTED.clear();
    }
}
