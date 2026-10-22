package com.example.aichat.config;

import com.example.aichat.AiChatMod;
import com.example.aichat.knowledge.KnowledgeReader;
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
import java.util.Locale;
import java.util.Map;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PLACEHOLDER_KEY = "sk-your-key-here";
    private static final String DEFAULT_SYSTEM_PROMPT_FILE = "system_prompt.txt";
    private static final String DEFAULT_PERSONA_FILE = "persona_prompt.txt";
    private static final String DEFAULT_JUDGE_PROMPT_FILE = "judge_prompt.txt";
    private static final String DEFAULT_REPLY_PROMPT_FILE = "reply_prompt.txt";
    private static final String LEGACY_PERSONA_FIELD = "persona_prompt";
    /**
     * 判断模型的默认输出预算。推理模型的思考 token 也算在 max_tokens 里，
     * 256 在 glm-4.7-flash 这类模型上会被 reasoning 吃光、content 变成空串。
     */
    private static final int DEFAULT_JUDGE_MAX_OUTPUT_TOKENS = 1024;
    private static final int CURRENT_CONFIG_VERSION = 3;
    private static final int CURRENT_SYSTEM_VERSION = 1;

    private static volatile ModConfig config;
    private static volatile SystemConfig systemConfig;
    private static Path modConfigDir;

    private ConfigManager() {
    }

    public static void init() {
        modConfigDir = FabricLoader.getInstance().getConfigDir().resolve("aichat");
        reload();
    }

    /**
     * 重新读取 config.json / system.json；/aichat reload 会调用这里。
     * 读取或解析失败时抛 RuntimeException，旧配置保持不变。
     */
    public static synchronized void reload() {
        try {
            Files.createDirectories(modConfigDir);
            copyIfMissing("config.json");

            Path configFile = modConfigDir.resolve("config.json");
            upgradeFile(configFile, "config.json", CURRENT_CONFIG_VERSION, "config_version");
            JsonObject root = JsonParser.parseString(
                    Files.readString(configFile, StandardCharsets.UTF_8)).getAsJsonObject();

            boolean rewriteConfig = migrateLegacyPersona(root);
            ModConfig loaded = GSON.fromJson(root, ModConfig.class);
            if (loaded == null) {
                loaded = new ModConfig();
            }
            applyDefaults(loaded);
            if (rewriteConfig) {
                Files.writeString(configFile, GSON.toJson(root), StandardCharsets.UTF_8);
            }

            copyIfMissing("system.json");
            Path systemFile = modConfigDir.resolve("system.json");
            upgradeFile(systemFile, "system.json", CURRENT_SYSTEM_VERSION, "version");
            SystemConfig loadedSystem = GSON.fromJson(
                    Files.readString(systemFile, StandardCharsets.UTF_8), SystemConfig.class);
            if (loadedSystem == null) {
                loadedSystem = new SystemConfig();
            }
            applySystemDefaults(loadedSystem);

            // 全部校验通过后再替换，避免加载失败时丢失旧配置
            config = loaded;
            systemConfig = loadedSystem;

            if (DEFAULT_SYSTEM_PROMPT_FILE.equals(loaded.system_prompt_file)) {
                copyIfMissing(DEFAULT_SYSTEM_PROMPT_FILE);
            }
            if (DEFAULT_PERSONA_FILE.equals(loaded.persona_prompt_file)) {
                copyIfMissing(DEFAULT_PERSONA_FILE);
            }
            copyIfMissing(DEFAULT_JUDGE_PROMPT_FILE);
            copyIfMissing(DEFAULT_REPLY_PROMPT_FILE);

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
        if (target.buffer_delay_seconds < 1) {
            target.buffer_delay_seconds = 5;
        }
        if (target.buffer_max_messages < 1) {
            target.buffer_max_messages = 10;
        }
        if (target.buffer_max_chars < 1) {
            target.buffer_max_chars = 500;
        }
    }

    private static void applySystemDefaults(SystemConfig target) {
        if (target.judge == null) {
            target.judge = new SystemConfig.ApiSettings();
        }
        if (target.reply == null) {
            target.reply = new SystemConfig.ApiSettings();
        }
        if (target.judge.system_prompt_file == null || target.judge.system_prompt_file.isBlank()) {
            target.judge.system_prompt_file = DEFAULT_JUDGE_PROMPT_FILE;
        }
        if (target.reply.system_prompt_file == null || target.reply.system_prompt_file.isBlank()) {
            target.reply.system_prompt_file = DEFAULT_REPLY_PROMPT_FILE;
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

    /**
     * 版本低于当前值时，备份原文件并用默认模板补齐缺失字段，已有值保持不变。
     */
    private static void upgradeFile(Path file, String resourceName, int currentVersion, String versionField)
            throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject user = JsonParser.parseString(text).getAsJsonObject();
        int version = 1;
        if (user.has(versionField) && user.get(versionField).isJsonPrimitive()) {
            try {
                version = user.get(versionField).getAsInt();
            } catch (NumberFormatException ignored) {
                version = 1;
            }
        }
        if (version >= currentVersion) {
            return;
        }

        JsonObject merged = mergeDefaults(defaultJson(resourceName), user);
        merged.addProperty(versionField, currentVersion);
        backup(file, version);
        Files.writeString(file, GSON.toJson(merged), StandardCharsets.UTF_8);
        AiChatMod.LOGGER.info("已升级 {}（v{} -> v{}），原文件已备份", resourceName, version, currentVersion);
    }

    private static JsonObject defaultJson(String name) throws IOException {
        try (InputStream in = ConfigManager.class
                .getResourceAsStream("/assets/aichat/default_config/" + name)) {
            if (in == null) {
                throw new IOException("缺少默认资源: " + name);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }

    private static JsonObject mergeDefaults(JsonObject defaults, JsonObject user) {
        JsonObject result = user.deepCopy();
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            String key = entry.getKey();
            JsonElement defaultValue = entry.getValue();
            if (!result.has(key)) {
                result.add(key, defaultValue.deepCopy());
            } else if (defaultValue.isJsonObject() && result.get(key).isJsonObject()) {
                result.add(key, mergeDefaults(
                        defaultValue.getAsJsonObject(),
                        result.get(key).getAsJsonObject()));
            }
        }
        return result;
    }

    private static void backup(Path file, int version) throws IOException {
        String name = file.getFileName() + ".v" + version + ".bak";
        Path backup = file.resolveSibling(name);
        if (!Files.exists(backup)) {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
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

    public static SystemConfig system() {
        return systemConfig;
    }

    public static Path dir() {
        return modConfigDir;
    }

    public static boolean isDualModelEnabled() {
        return systemConfig != null && systemConfig.dual_model_enabled;
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

    /**
     * 读取提示词模板并替换知识库列表、人设占位符。
     */
    public static String readPrompt(String fileName) {
        try {
            String template = Files.readString(modConfigDir.resolve(fileName), StandardCharsets.UTF_8);
            String files = String.join("\n", KnowledgeReader.listFiles());
            if (files.isBlank()) {
                files = "（无）";
            }
            String persona = readPersonaPrompt();
            return template
                    .replace("{KNOWLEDGE_FILE_LIST}", files)
                    .replace("{PERSONA_PROMPT}", persona);
        } catch (IOException e) {
            throw new RuntimeException("读取提示词文件失败: " + fileName, e);
        }
    }

    public static ApiProfile singleProfile() {
        ModConfig current = config;
        return new ApiProfile(
                current.api_url,
                current.api_key,
                current.model,
                current.api_type,
                readPrompt(current.system_prompt_file),
                current.enable_tools,
                current.use_json_mode,
                current.enable_thinking,
                current.send_thinking_field,
                current.reasoning_effort,
                current.max_output_tokens,
                current.thinking_budget_tokens,
                current.system_prompt_in_user
        );
    }

    public static ApiProfile judgeProfile() {
        ModConfig current = config;
        SystemConfig.ApiSettings settings = systemConfig.judge;
        String apiUrl = or(settings.api_url, current.api_url);
        String model = or(settings.model, current.model);
        // 判断任务只需要一行 JSON。推理模型默认开着思考，会把输出预算全烧在 reasoning_content 上，
        // 导致 content 为空、判断永远"忽略"，所以在支持开关的平台上主动关闭思考。
        boolean disableThinking = Endpoints.supportsThinkingToggle(apiUrl, model);
        return new ApiProfile(
                apiUrl,
                or(settings.api_key, current.api_key),
                model,
                or(settings.api_type, current.api_type),
                readPrompt(or(settings.system_prompt_file, DEFAULT_JUDGE_PROMPT_FILE)),
                false,
                true,
                false,
                disableThinking,
                "",
                settings.max_output_tokens > 0 ? settings.max_output_tokens : DEFAULT_JUDGE_MAX_OUTPUT_TOKENS,
                0,
                current.system_prompt_in_user
        );
    }

    public static ApiProfile replyProfile() {
        ModConfig current = config;
        SystemConfig.ApiSettings settings = systemConfig.reply;
        return new ApiProfile(
                or(settings.api_url, current.api_url),
                or(settings.api_key, current.api_key),
                or(settings.model, current.model),
                or(settings.api_type, current.api_type),
                readPrompt(or(settings.system_prompt_file, DEFAULT_REPLY_PROMPT_FILE)),
                current.enable_tools,
                current.use_json_mode,
                current.enable_thinking,
                current.send_thinking_field,
                current.reasoning_effort,
                settings.max_output_tokens > 0 ? settings.max_output_tokens : current.max_output_tokens,
                settings.thinking_budget_tokens > 0 ? settings.thinking_budget_tokens : current.thinking_budget_tokens,
                current.system_prompt_in_user
        );
    }

    public static String usageModelName() {
        if (!isDualModelEnabled()) {
            return config.model;
        }
        return or(systemConfig.judge.model, config.model)
                + "+" + or(systemConfig.reply.model, config.model);
    }

    public static boolean hasUsableApiKey() {
        if (isDualModelEnabled()) {
            String judgeKey = or(systemConfig.judge.api_key, config.api_key);
            String replyKey = or(systemConfig.reply.api_key, config.api_key);
            String judgeUrl = or(systemConfig.judge.api_url, config.api_url);
            String replyUrl = or(systemConfig.reply.api_url, config.api_url);
            return isKeyUsable(judgeKey, judgeUrl) && isKeyUsable(replyKey, replyUrl);
        }
        return isKeyUsable(config.api_key, config.api_url);
    }

    public static boolean isApiKeyConfigured() {
        return hasUsableApiKey();
    }

    private static boolean isKeyUsable(String key, String url) {
        String value = key == null ? "" : key.trim();
        if (!value.isEmpty() && !PLACEHOLDER_KEY.equals(value)) {
            return true;
        }
        // 本地推理服务（Ollama / LM Studio / vLLM）通常不需要 Key
        String normalized = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("[::1]");
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
