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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * token 用量按行追加到 config/aichat/usage.jsonl，一行一次完整请求。
 * 纯文本、可手工查看，不依赖数据库；统计时按本机时区归日。
 */
public final class UsageStore {
    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static Path file;

    private UsageStore() {
    }

    public static void init() {
        file = ConfigManager.dir().resolve("usage.jsonl");
    }

    public static void record(String playerName, String model, TokenUsage usage) {
        if (usage == null || usage.total() <= 0) {
            return;
        }
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
     * 清空统计：把当前 usage.jsonl 改名备份，后续记录从零开始。
     */
    public static boolean clear() {
        synchronized (LOCK) {
            Path path = file;
            if (path == null || !Files.exists(path)) {
                return false;
            }
            try {
                String stamp = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Files.move(
                        path,
                        path.resolveSibling("usage-" + stamp + ".jsonl"),
                        StandardCopyOption.REPLACE_EXISTING
                );
                return true;
            } catch (IOException e) {
                AiChatMod.LOGGER.warn("清空 token 用量失败: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * 返回最近 days 天（含今天）的按日汇总，没有记录的天也会返回 0。
     */
    public static List<DailyUsage> lastDays(int days) {
        int span = Math.max(1, days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();

        Path path = file;
        synchronized (LOCK) {
            if (path != null && Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        accumulate(line, from, buckets);
                    }
                } catch (IOException e) {
                    AiChatMod.LOGGER.warn("读取 token 用量失败: {}", e.getMessage());
                }
            }
        }

        List<DailyUsage> result = new ArrayList<>(span);
        ZoneId zone = ZoneId.systemDefault();
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

    private static void accumulate(String line, LocalDate from, Map<LocalDate, long[]> buckets) {
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

    private static long readLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }

    public record DailyUsage(String date, long prompt, long completion, long total, int requests) {
    }
}
