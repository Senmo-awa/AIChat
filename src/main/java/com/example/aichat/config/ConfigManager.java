package com.example.aichat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    private static ModConfig config;
    private static Path modConfigDir;

    private ConfigManager() {
    }

    public static void init() {
        modConfigDir = FabricLoader.getInstance().getConfigDir().resolve("aichat");
        try {
            Files.createDirectories(modConfigDir);
            copyIfMissing("config.json");
            copyIfMissing("system_prompt.txt");

            Path configFile = modConfigDir.resolve("config.json");
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            config = GSON.fromJson(json, ModConfig.class);
            if (config == null) {
                config = new ModConfig();
            }
            if (config.knowledge_dir == null || config.knowledge_dir.isBlank()) {
                config.knowledge_dir = "knowledge";
            }
            Files.createDirectories(modConfigDir.resolve(config.knowledge_dir));
        } catch (IOException e) {
            throw new RuntimeException("无法初始化 AI Chat 配置", e);
        }
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

    public static boolean isApiKeyConfigured() {
        if (config == null || config.api_key == null) {
            return false;
        }
        String key = config.api_key.trim();
        return !key.isEmpty() && !PLACEHOLDER_KEY.equals(key);
    }
}
