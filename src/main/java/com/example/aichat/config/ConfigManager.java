package com.example.aichat.config;

import com.example.aichat.AiChatMod;
import com.example.aichat.knowledge.KnowledgeReader;
import com.example.aichat.memory.MemoryStore;
import com.example.aichat.servertools.ServerToolAccess;
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
import java.util.Map;

/**
 * 配置读写入口。
 * <p>
 * 2.0.0 起 config.json / system.json 由 {@link ConfigDoc} 生成：文件里带 {@code //} 注释，
 * 读取时用宽松 JSON 读法跳过注释（Gson 的 JsonParser 本身就是宽松读），写入时按字段表重新渲染。
 * 平时（版本未变）完全不重写用户的文件，所以玩家自己加的注释、缩进、空行都会原样保留；
 * 只有 config_version 变高时才会备份并重写一次，把新字段和新说明补进去。
 */
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
    /** 配置版本：6 = 2.0.0（带注释的配置 + 服务器管理工具）。 */
    public static final int CURRENT_CONFIG_VERSION = 6;
    /** system.json 版本：2 = 带注释的渲染。 */
    public static final int CURRENT_SYSTEM_VERSION = 2;

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

            Path configFile = modConfigDir.resolve("config.json");
            JsonObject root = loadOrCreate(
                    configFile, ConfigDoc.CONFIG_ENTRIES, CURRENT_CONFIG_VERSION,
                    "config_version", "config.json", false);

            boolean rewriteConfig = migrateLegacyPersona(root);
            ModConfig loaded = GSON.fromJson(root, ModConfig.class);
            if (loaded == null) {
                loaded = new ModConfig();
            }
            applyDefaults(loaded);
            if (rewriteConfig) {
                writeRendered(configFile, ConfigDoc.CONFIG_ENTRIES, root, false);
            }

            Path systemFile = modConfigDir.resolve("system.json");
            JsonObject systemRoot = loadOrCreate(
                    systemFile, ConfigDoc.SYSTEM_ENTRIES, CURRENT_SYSTEM_VERSION,
                    "version", "system.json", true);
            SystemConfig loadedSystem = GSON.fromJson(systemRoot, SystemConfig.class);
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
            if (loaded.memory_enabled) {
                Files.createDirectories(modConfigDir.resolve(loaded.memory_dir));
            }
            ServerToolAccess.reloadAdminList();
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
        if (target.memory_dir == null || target.memory_dir.isBlank()) {
            target.memory_dir = "memory";
        }
        if (target.memory_max_file_kb < 1) {
            target.memory_max_file_kb = 8;
        }
        applyServerToolDefaults(target);
    }

    private static void applyServerToolDefaults(ModConfig target) {
        if (target.server_tools_prefix == null) {
            target.server_tools_prefix = "!+";
        }
        if (target.server_tools_permission_mode == null || target.server_tools_permission_mode.isBlank()) {
            target.server_tools_permission_mode = "op";
        }
        target.server_tools_permission_mode = target.server_tools_permission_mode.trim().toLowerCase();
        if (target.server_tools_permission_level < 1 || target.server_tools_permission_level > 4) {
            target.server_tools_permission_level = 4;
        }
        if (target.server_tools_luckperms_node == null || target.server_tools_luckperms_node.isBlank()) {
            target.server_tools_luckperms_node = "aichat.servertools";
        }
        if (target.server_tools_admins_file == null || target.server_tools_admins_file.isBlank()) {
            target.server_tools_admins_file = "server_admins.json";
        }
        if (target.server_tools_read_max_kb < 1) {
            target.server_tools_read_max_kb = 48;
        }
        if (target.server_tools_write_max_kb < 1) {
            target.server_tools_write_max_kb = 64;
        }
        if (target.server_tools_list_max_entries < 1) {
            target.server_tools_list_max_entries = 200;
        }
        if (target.server_tools_broadcast == null || target.server_tools_broadcast.isBlank()) {
            target.server_tools_broadcast = "show";
        }
        target.server_tools_broadcast = target.server_tools_broadcast.trim().toLowerCase();
        if (target.server_tools_broadcast_flag == null || target.server_tools_broadcast_flag.isBlank()) {
            target.server_tools_broadcast_flag = "-f";
        }
        if (target.server_tools_deny_paths == null) {
            target.server_tools_deny_paths = new String[0];
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
     * 读取配置文档：文件不存在时按字段表生成（带注释），版本偏低时备份后重写，
     * 版本一致时原样读、一个字节都不动。
     */
    private static JsonObject loadOrCreate(Path file, java.util.List<?> entries, int currentVersion,
                                           String versionField, String displayName, boolean system)
            throws IOException {
        if (!Files.exists(file)) {
            writeRendered(file, entries, ConfigDoc.defaults(entries), system);
            AiChatMod.LOGGER.info("已生成默认配置：{}", displayName);
            return ConfigDoc.defaults(entries);
        }

        JsonObject user = readLenient(file, displayName);
        int version = 1;
        if (user.has(versionField) && user.get(versionField).isJsonPrimitive()) {
            try {
                version = user.get(versionField).getAsInt();
            } catch (NumberFormatException ignored) {
                version = 1;
            }
        }
        if (version >= currentVersion) {
            return user;
        }

        JsonObject merged = mergeDefaults(ConfigDoc.defaults(entries), user);
        merged.addProperty(versionField, currentVersion);
        backup(file, version);
        writeRendered(file, entries, merged, system);
        AiChatMod.LOGGER.info("已升级 {}（v{} -> v{}），原文件已备份，新字段与注释已补齐",
                displayName, version, currentVersion);
        return merged;
    }

    /**
     * 宽松读法：JsonParser.parseString 内部就是宽松模式，会跳过行注释与块注释，
     * 所以配置文件里写注释不影响解析；解析失败时把行号信息原样带出来。
     */
    public static JsonObject readLenient(Path file, String displayName) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element == null || !element.isJsonObject()) {
                throw new IOException(displayName + " 的根节点必须是一个 JSON 对象");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(displayName + " 解析失败：" + e.getMessage(), e);
        }
    }

    private static void writeRendered(Path file, java.util.List<?> entries, JsonObject values,
                                      boolean system) throws IOException {
        String text = system
                ? ConfigDoc.renderSystem(values)
                : ConfigDoc.renderConfig(values);
        Files.writeString(file, text, StandardCharsets.UTF_8);
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
     * 读取提示词模板并替换知识库列表、人设占位符（不带工具说明，判断模型用这个）。
     */
    public static String readPrompt(String fileName) {
        return readPrompt(fileName, false);
    }

    /**
     * 读取提示词模板。
     *
     * @param toolGuides 是否在末尾追加工具说明（记忆 + 服务器管理工具）。这些规则由模组生成、
     *                   不写进用户的 system_prompt.txt：升级时不会覆盖别人改过的提示词，
     *                   也避免给只有判断任务的 judge 塞它用不到的说明。
     */
    public static String readPrompt(String fileName, boolean toolGuides) {
        try {
            String template = Files.readString(modConfigDir.resolve(fileName), StandardCharsets.UTF_8);
            String files = String.join("\n", KnowledgeReader.listFiles());
            if (files.isBlank()) {
                files = "（无）";
            }
            String persona = readPersonaPrompt();
            String prompt = template
                    .replace("{KNOWLEDGE_FILE_LIST}", files)
                    .replace("{PERSONA_PROMPT}", persona);
            if (toolGuides) {
                if (MemoryStore.enabled()) {
                    prompt = prompt + MemoryStore.promptSection();
                }
                prompt = prompt + ServerToolAccess.promptSection();
            }
            return prompt;
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
                readPrompt(current.system_prompt_file, true),
                current.enable_tools,
                current.memory_enabled,
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
                readPrompt(or(settings.system_prompt_file, DEFAULT_REPLY_PROMPT_FILE), true),
                current.enable_tools,
                current.memory_enabled,
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
        String normalized = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("[::1]");
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
}
