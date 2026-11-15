package com.example.aichat.usage;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * token 用量按行追加到 config/aichat/usage.jsonl，一行一次完整请求。
 * 纯文本、可手工查看，不依赖数据库；统计时按本机时区归日。
 * <p>
 * 每行带一个 {@code channel} 字段区分账本：
 * <ul>
 *   <li>{@link #CHANNEL_PUBLIC}：公屏聊天（含 join/quit、特权请求）——默认账本；</li>
 *   <li>{@link #CHANNEL_PRIVATE}：私聊（{@code /aichat pm}）。</li>
 * </ul>
 * 旧版本写的行没有该字段，一律当公开账本处理，升级后历史数据不会丢。
 * <p>
 * 清空可以只清公开账本（默认），私聊消耗只有在 {@code /aichat token clear all} 时才一起清掉。
 */
public final class UsageStore {
    /** 公屏账本。 */
    public static final String CHANNEL_PUBLIC = "public";
    /** 私聊账本。 */
    public static final String CHANNEL_PRIVATE = "pm";

    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static Path file;
    /** 今日累计的缓存（避免每条聊天都读一遍 usage.jsonl）；record / clear 时置 -1 失效。 */
    private static volatile long todayCacheTotal = -1L;
    private static volatile long todayCacheAt;
    private static final long TODAY_CACHE_MS = 30_000L;

    private UsageStore() {
    }

    public static void init() {
        file = ConfigManager.dir().resolve("usage.jsonl");
    }

    public static void record(String playerName, String model, TokenUsage usage) {
        record(playerName, model, usage, CHANNEL_PUBLIC);
    }

    public static void record(String playerName, String model, TokenUsage usage, String channel) {
        if (usage == null || usage.total() <= 0) {
            return;
        }
        todayCacheTotal = -1L;
        String bucket = channel == null || channel.isBlank() ? CHANNEL_PUBLIC : channel;
        synchronized (LOCK) {
            try {
                Path path = file;
                if (path == null) {
                    return;
                }
                Files.createDirectories(path.getParent());
                JsonObject line = new JsonObject();
                line.addProperty("time", System.currentTimeMillis());
                line.addProperty("player", playerName);
                line.addProperty("model", model);
                line.addProperty("channel", bucket);
                line.addProperty("prompt", usage.prompt());
                line.addProperty("completion", usage.completion());
                line.addProperty("total", usage.total());
                Files.writeString(
                        path,
                        GSON.toJson(line) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                AiChatMod.LOGGER.warn("写入 token 用量失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 今日累计 token（公开 + 私聊，按本机时区归日）。用于每日预算判定——
     * 预算管的是总花费，私聊当然也算钱。
     * <p>
     * 30 秒缓存 + 每次写入后失效：聊天密集时不会每条消息都去解析整个 usage.jsonl，
     * 又能保证预算判断大致跟得上（最多滞后一次请求）。
     */
    public static long todayTotal() {
        long cached = todayCacheTotal;
        if (cached >= 0 && System.currentTimeMillis() - todayCacheAt < TODAY_CACHE_MS) {
            return cached;
        }
        long total = 0L;
        for (DailyUsage day : lastDays(1, null)) {
            total = day.total();
        }
        todayCacheTotal = total;
        todayCacheAt = System.currentTimeMillis();
        return total;
    }

    /**
     * 清空公开账本，保留私聊记录：把公开那部分另存为 {@code usage-<时间戳>.jsonl} 备份，
     * {@code usage.jsonl} 里只留下私聊行。
     */
    public static boolean clear() {
        return clear(false);
    }

    /**
     * @param all true 清空全部（公开 + 私聊，整份备份后从零开始）；
     *            false 只清公开账本，私聊记录原样保留。
     */
    public static boolean clear(boolean all) {
        todayCacheTotal = -1L;
        synchronized (LOCK) {
            Path path = file;
            if (path == null || !Files.exists(path)) {
                return false;
            }
            try {
                String stamp = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path backup = path.resolveSibling("usage-" + stamp + ".jsonl");
                if (all) {
                    Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
                List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
                List<String> publicLines = new ArrayList<>();
                List<String> privateLines = new ArrayList<>();
                for (String line : allLines) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    (isPrivateLine(line) ? privateLines : publicLines).add(line);
                }
                if (publicLines.isEmpty()) {
                    return false;
                }
                Files.writeString(backup, String.join(System.lineSeparator(), publicLines)
                        + System.lineSeparator(), StandardCharsets.UTF_8);
                if (privateLines.isEmpty()) {
                    Files.deleteIfExists(path);
                } else {
                    Files.writeString(path, String.join(System.lineSeparator(), privateLines)
                            + System.lineSeparator(), StandardCharsets.UTF_8);
                }
                return true;
            } catch (IOException e) {
                AiChatMod.LOGGER.warn("清空 token 用量失败: {}", e.getMessage());
                return false;
            }
        }
    }

    /** 最近 days 天的公开账本按日汇总（/aichat token 的表格用）。 */
    public static List<DailyUsage> lastDays(int days) {
        return lastDays(days, CHANNEL_PUBLIC);
    }

    /**
     * 返回最近 days 天（含今天）的按日汇总，没有记录的天也会返回 0。
     *
     * @param channel 只看该账本；{@code null} 表示全部账本
     */
    public static List<DailyUsage> lastDays(int days, String channel) {
        int span = Math.max(1, days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();

        Path path = file;
        synchronized (LOCK) {
            if (path != null && Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        accumulate(line, from, buckets, channel);
                    }
                } catch (IOException e) {
                    AiChatMod.LOGGER.warn("读取 token 用量失败: {}", e.getMessage());
                }
            }
        }

        List<DailyUsage> result = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate date = from.plusDays(i);
            long[] value = buckets.get(date);
            if (value == null) {
                result.add(new DailyUsage(date.toString(), 0, 0, 0, 0));
            } else {
                result.add(new DailyUsage(date.toString(), value[0], value[1], value[2], (int) value[3]));
            }
        }
        return result;
    }

    /**
     * 私聊账本按玩家汇总（总量从大到小），用于「看不同玩家的私聊 token」。
     *
     * @param days 最近多少天（含今天）；0 或负数表示全部历史
     */
    public static List<PlayerUsage> privateByPlayer(int days) {
        return byPlayer(days, CHANNEL_PRIVATE);
    }

    /** 指定账本按玩家汇总（总量从大到小）。 */
    public static List<PlayerUsage> byPlayer(int days, String channel) {
        return aggregate(days, channel, "player");
    }

    /**
     * 按模型汇总（多模型模式用）：{@code model} 字段记的是模型 id。
     * 公屏与私聊一起算——看的是「哪个模型在花钱」。
     */
    public static List<ModelUsage> byModel(int days) {
        List<PlayerUsage> raw = aggregate(days, null, "model");
        List<ModelUsage> result = new ArrayList<>(raw.size());
        for (PlayerUsage usage : raw) {
            result.add(new ModelUsage(usage.player(), usage.prompt(), usage.completion(),
                    usage.total(), usage.requests()));
        }
        return result;
    }

    private static List<PlayerUsage> aggregate(int days, String channel, String field) {
        LocalDate from = days > 0 ? LocalDate.now().minusDays(days - 1L) : null;
        Map<String, long[]> buckets = new LinkedHashMap<>();

        Path path = file;
        synchronized (LOCK) {
            if (path != null && Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        accumulateKey(line, from, buckets, channel, field);
                    }
                } catch (IOException e) {
                    AiChatMod.LOGGER.warn("读取 token 用量失败: {}", e.getMessage());
                }
            }
        }

        List<PlayerUsage> result = new ArrayList<>(buckets.size());
        for (Map.Entry<String, long[]> entry : buckets.entrySet()) {
            long[] value = entry.getValue();
            result.add(new PlayerUsage(entry.getKey(), value[0], value[1], value[2], (int) value[3]));
        }
        result.sort(Comparator.comparingLong(PlayerUsage::total).reversed());
        return result;
    }

    private static void accumulate(String line, LocalDate from, Map<LocalDate, long[]> buckets,
                                   String channel) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(line);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            if (!object.has("time") || object.get("time").isJsonNull()) {
                return;
            }
            if (channel != null && !channel.equals(readChannel(object))) {
                return;
            }
            LocalDate date = Instant.ofEpochMilli(object.get("time").getAsLong())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            if (date.isBefore(from)) {
                return;
            }
            long[] bucket = buckets.computeIfAbsent(date, k -> new long[4]);
            bucket[0] += readLong(object, "prompt");
            bucket[1] += readLong(object, "completion");
            bucket[2] += readLong(object, "total");
            bucket[3]++;
        } catch (JsonParseException | NumberFormatException | IllegalStateException ignored) {
            // 跳过损坏的行，不影响其他统计
        }
    }

    private static void accumulatePlayer(String line, LocalDate from, Map<String, long[]> buckets,
                                         String channel) {
        accumulateKey(line, from, buckets, channel, "player");
    }

    private static void accumulateKey(String line, LocalDate from, Map<String, long[]> buckets,
                                      String channel, String field) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(line);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            if (!object.has("time") || object.get("time").isJsonNull()) {
                return;
            }
            if (channel != null && !channel.equals(readChannel(object))) {
                return;
            }
            if (from != null) {
                LocalDate date = Instant.ofEpochMilli(object.get("time").getAsLong())
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();
                if (date.isBefore(from)) {
                    return;
                }
            }
            String label = object.has(field) && !object.get(field).isJsonNull()
                    ? object.get(field).getAsString()
                    : "（未知）";
            long[] bucket = buckets.computeIfAbsent(label, k -> new long[4]);
            bucket[0] += readLong(object, "prompt");
            bucket[1] += readLong(object, "completion");
            bucket[2] += readLong(object, "total");
            bucket[3]++;
        } catch (JsonParseException | NumberFormatException | IllegalStateException ignored) {
            // 跳过损坏的行，不影响其他统计
        }
    }

    private static boolean isPrivateLine(String line) {
        try {
            JsonElement root = JsonParser.parseString(line);
            return root.isJsonObject() && CHANNEL_PRIVATE.equals(readChannel(root.getAsJsonObject()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 老版本没有 channel 字段，按公开账本处理。 */
    private static String readChannel(JsonObject object) {
        JsonElement element = object.get("channel");
        if (element == null || element.isJsonNull()) {
            return CHANNEL_PUBLIC;
        }
        String value = element.getAsString().trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? CHANNEL_PUBLIC : value;
    }

    private static long readLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }

    public record DailyUsage(String date, long prompt, long completion, long total, int requests) {
    }

    public record PlayerUsage(String player, long prompt, long completion, long total, int requests) {
    }

    /** 按模型汇总的一行（多模型模式）。 */
    public record ModelUsage(String model, long prompt, long completion, long total, int requests) {
    }
}
