package com.example.aichat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PLACEHOLDER_KEY = "sk-your-key-here";
    private static final String DEFAULT_SYSTEM_PROMPT_FILE = "system_prompt.txt";
    private static final String DEFAULT_PERSONA_FILE = "persona_prompt.txt";
    private static final String LEGACY_PERSONA_FIELD = "persona_prompt";

    private static volatile ModConfig config;
    private static Path modConfigDir;

    private ConfigManager() {
    }

    public static void init() {
        modConfigDir = FabricLoader.getInstance().getConfigDir().resolve("aichat");
        reload();
    }

    /**
     * 重新读取 config.json；/reload 命令会调用这里。
     * 读取或解析失败时抛 RuntimeException，旧配置保持不变。
     */
    public static synchronized void reload() {
        try {
            Files.createDirectories(modConfigDir);
            copyIfMissing("config.json");

            Path configFile = modConfigDir.resolve("config.json");
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            boolean rewriteConfig = migrateLegacyPersona(root);

            ModConfig loaded = GSON.fromJson(root, ModConfig.class);
            if (loaded == null) {
                loaded = new ModConfig();
            }
            applyDefaults(loaded);

            // 全部校验通过后再替换，避免加载失败时丢失旧配置
            config = loaded;

            if (rewriteConfig) {
                Files.writeString(configFile, GSON.toJson(root), StandardCharsets.UTF_8);
            }

            if (DEFAULT_SYSTEM_PROMPT_FILE.equals(loaded.system_prompt_file)) {
                copyIfMissing(DEFAULT_SYSTEM_PROMPT_FILE);
            }
            if (DEFAULT_PERSONA_FILE.equals(loaded.persona_prompt_file)) {
                copyIfMissing(DEFAULT_PERSONA_FILE);
            }

            Files.createDirectories(modConfigDir.resolve(loaded.knowledge_dir));
        } catch (IOException | JsonParseException e) {
            throw new RuntimeException("无法加载 AI Chat 配置: " + e.getMessage(), e);
        }
    }

    private static void applyDefaults(ModConfig target) {
        if (target.knowledge_dir == null || target.knowledge_dir.isBlank()) {
            target.knowledge_dir = "knowledge";
        }
        if (target.system_prompt_file == null || target.system_prompt_file.isBlank()) {
            target.system_prompt_file = DEFAULT_SYSTEM_PROMPT_FILE;
        }
        if (target.persona_prompt_file == null || target.persona_prompt_file.isBlank()) {
            target.persona_prompt_file = DEFAULT_PERSONA_FILE;
        }
        if (target.reply_prefix == null) {
            target.reply_prefix = "[AI] ";
        }
    }

    /**
     * 1.0.x 把人设保存在 config.json 的 persona_prompt 字段中。
     * 升级时迁移到 persona_prompt.txt，并从 config.json 移除该字段。
     */
    private static boolean migrateLegacyPersona(JsonObject root) throws IOException {
        if (!root.has(LEGACY_PERSONA_FIELD)) {
            return false;
        }
        JsonElement legacy = root.get(LEGACY_PERSONA_FIELD);
        if (legacy != null && legacy.isJsonPrimitive() && !legacy.getAsString().isBlank()) {
            Path personaFile = modConfigDir.resolve(DEFAULT_PERSONA_FILE);
            if (!Files.exists(personaFile)) {
                Files.writeString(personaFile, legacy.getAsString(), StandardCharsets.UTF_8);
            }
        }
        root.remove(LEGACY_PERSONA_FIELD);
        return true;
    }

    private static void copyIfMissing(String name) throws IOException {
        Path target = modConfigDir.resolve(name);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = ConfigManager.class
                .getResourceAsStream("/assets/aichat/default_config/" + name)) {
            if (in == null) {
                throw new IOException("缺少默认资源: " + name);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static ModConfig get() {
        return config;
    }

    public static Path dir() {
        return modConfigDir;
    }

    public static String readSystemPrompt() throws IOException {
        return Files.readString(
                modConfigDir.resolve(config.system_prompt_file),
                StandardCharsets.UTF_8
        );
    }

    public static String readPersonaPrompt() throws IOException {
        Path base = modConfigDir.normalize();
        Path file = base.resolve(config.persona_prompt_file).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static boolean isApiKeyConfigured() {
        if (config == null || config.api_key == null) {
            return false;
        }
        String key = config.api_key.trim();
        return !key.isEmpty() && !PLACEHOLDER_KEY.equals(key);
    }
}
