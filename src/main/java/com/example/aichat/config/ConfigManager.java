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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置读写入口。
 * <p>
 * 2.0.0 起 config.json / system.json 由 {@link ConfigDoc} 生成：文件里带 {@code //} 注释，
 * 读取时用宽松 JSON 读法跳过注释（Gson 的 JsonParser 本身就是宽松读），写入时按字段表重新渲染。
 * 平时（版本未变）完全不重写用户的文件，所以玩家自己加的注释、缩进、空行都会原样保留；
 * 只有 config_version 低于当前模组版本时才会备份并重写一次，把新字段和新说明补进去。
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
    /**
     * 配置文件版本：直接用模组版本号（如 {@code "2.0.3"}），升级模组后版本号变了就补齐新字段并重写一次。
     * <p>
     * 比以前的整数计数器直观：配置文件里写着 {@code "config_version": "2.0.2"} 一眼就知道是哪个版本的配置。
     * 取的是模组元数据里的版本，开发号后缀（{@code -dev.N}）会被去掉，同一发布号的多个开发构建
     * 不会反复重写用户文件。
     */
    public static final String CURRENT_CONFIG_VERSION = resolveModVersion();
    /** system.json 与 config.json 用同一个版本号，方便对照。 */
    public static final String CURRENT_SYSTEM_VERSION = CURRENT_CONFIG_VERSION;
    /** 取不到模组元数据时（极少数加载顺序问题）退回这个版本号。 */
    private static final String FALLBACK_VERSION = "2.0.3";

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
        applyIgnoredPrefixDefaults(target);
        applyIgnoredPlayerDefaults(target);
        applyGuardDefaults(target);
        if (target.memory_dir == null || target.memory_dir.isBlank()) {
            target.memory_dir = "memory";
        }
        if (target.memory_max_file_kb < 1) {
            target.memory_max_file_kb = 8;
        }
        applyServerToolDefaults(target);
    }

    /**
     * 忽略前缀的兜底与清洗：null 补默认 {@code ["!!"]}，去掉空白项并 trim。
     * 注意「空数组」是合法配置（明确表示不忽略任何前缀），这里不会把空数组换成默认值。
     */
    private static void applyIgnoredPrefixDefaults(ModConfig target) {
        if (target.ignored_chat_prefixes == null) {
            target.ignored_chat_prefixes = new ArrayList<>(List.of("!!"));
            return;
        }
        List<String> cleaned = new ArrayList<>(target.ignored_chat_prefixes.size());
        for (String prefix : target.ignored_chat_prefixes) {
            if (prefix != null && !prefix.isBlank()) {
                cleaned.add(prefix.trim());
            }
        }
        target.ignored_chat_prefixes = cleaned;
    }

    /**
     * 忽略玩家前缀的兜底与清洗：null 补默认 {@code ["bot_"]}，去掉空白项并 trim。
     * 空数组同样是合法配置（表示不忽略任何玩家），不会被换回默认值。
     */
    private static void applyIgnoredPlayerDefaults(ModConfig target) {
        if (target.ignored_player_prefixes == null) {
            target.ignored_player_prefixes = new ArrayList<>(List.of("bot_"));
            return;
        }
        List<String> cleaned = new ArrayList<>(target.ignored_player_prefixes.size());
        for (String prefix : target.ignored_player_prefixes) {
            if (prefix != null && !prefix.isBlank()) {
                cleaned.add(prefix.trim());
            }
        }
        target.ignored_player_prefixes = cleaned;
    }

    /**
     * 省钱 / 防滥用字段的兜底：负数当 0（= 关闭）处理；点名词为 null 或空时回到默认，
     * 避免「开了被动回复但一个词都没填」把自己变成永远不回话。
     */
    private static void applyGuardDefaults(ModConfig target) {
        if (target.player_cooldown_seconds < 0) {
            target.player_cooldown_seconds = 0;
        }
        if (target.daily_token_budget < 0) {
            target.daily_token_budget = 0;
        }
        if (target.mention_names == null || target.mention_names.isEmpty()) {
            target.mention_names = new ArrayList<>(List.of("小墨"));
            return;
        }
        List<String> cleaned = new ArrayList<>(target.mention_names.size());
        for (String name : target.mention_names) {
            if (name != null && !name.isBlank()) {
                cleaned.add(name.trim());
            }
        }
        target.mention_names = cleaned.isEmpty()
                ? new ArrayList<>(List.of("小墨"))
                : cleaned;
    }

    /**
     * 这条聊天内容是否属于「不送给 AI」的指令类消息。
     * <p>
     * 判定在进入缓冲区之前完成：命中前缀的消息既不进缓冲、也不进历史上下文，连请求都不会发。
     * 前导空格会被忽略（{@code "  !!ledger"} 同样算指令）。
     */
    public static boolean isIgnoredChat(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        ModConfig current = config;
        if (current == null || current.ignored_chat_prefixes == null) {
            return false;
        }
        String trimmed = content.stripLeading();
        for (String prefix : current.ignored_chat_prefixes) {
            if (prefix != null && !prefix.isEmpty() && trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个玩家是否属于「不送给 AI」的假人/机器人。
     * <p>
     * 判定发生在进缓冲区之前，聊天与加入/退出事件一视同仁：命中者既不进缓冲、
     * 也不进历史上下文，连请求都不会发。比较时忽略大小写，{@code BOT_Farm} 同样算。
     */
    public static boolean isIgnoredPlayer(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return false;
        }
        ModConfig current = config;
        if (current == null || current.ignored_player_prefixes == null) {
            return false;
        }
        for (String prefix : current.ignored_player_prefixes) {
            if (prefix == null || prefix.isEmpty()) {
                continue;
            }
            if (playerName.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
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
     * 读取配置文档：文件不存在时按字段表生成（带注释），版本低于当前版本时备份后重写，
     * 版本一致时原样读、一个字节都不动。
     */
    private static JsonObject loadOrCreate(Path file, java.util.List<?> entries, String currentVersion,
                                           String versionField, String displayName, boolean system)
            throws IOException {
        if (!Files.exists(file)) {
            writeRendered(file, entries, ConfigDoc.defaults(entries), system);
            AiChatMod.LOGGER.info("已生成默认配置：{}（版本 {}）", displayName, currentVersion);
            return ConfigDoc.defaults(entries);
        }

        JsonObject user = readLenient(file, displayName);
        String version = readVersion(user, versionField);
        if (!isOlderThan(version, currentVersion)) {
            return user;
        }

        JsonObject merged = mergeDefaults(ConfigDoc.defaults(entries), user);
        merged.addProperty(versionField, currentVersion);
        backup(file, version);
        writeRendered(file, entries, merged, system);
        AiChatMod.LOGGER.info("已升级 {}（{} -> {}），原文件已备份，新字段与注释已补齐",
                displayName, version.isBlank() ? "未知" : version, currentVersion);
        return merged;
    }

    /** 取模组版本号作为配置版本；去掉开发号后缀（{@code 2.0.3-dev.1} -> {@code 2.0.3}）。 */
    private static String resolveModVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("aichat")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .map(ConfigManager::baseVersion)
                    .orElse(FALLBACK_VERSION);
        } catch (RuntimeException | LinkageError e) {
            return FALLBACK_VERSION;
        }
    }

    /** 版本号只取主段：{@code 2.0.3-dev.1+build5} -> {@code 2.0.3}。 */
    private static String baseVersion(String raw) {
        String value = raw == null ? "" : raw.trim();
        int cut = value.indexOf('-');
        if (cut > 0) {
            value = value.substring(0, cut);
        }
        cut = value.indexOf('+');
        if (cut > 0) {
            value = value.substring(0, cut);
        }
        return value.isBlank() ? FALLBACK_VERSION : value;
    }

    /** 读文件里的版本值：老配置是整数（{@code 9}），新配置是版本号字符串（{@code "2.0.3"}），都按字符串取。 */
    private static String readVersion(JsonObject user, String versionField) {
        if (user == null || !user.has(versionField)) {
            return "";
        }
        var element = user.get(versionField);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            return element.getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 文件里的版本是否比当前版本旧（旧了才备份重写）。
     * <p>
     * 兼容三种写法：{@code "2.0.3"}（版本号）、{@code "9"}（2.0.2 及以前的整数计数器，按 {@code 0.0.9} 处理，
     * 一定低于任何 {@code 2.x}）、以及认不出来的字符串（一律当旧的，反正重写前会备份）。
     */
    static boolean isOlderThan(String userVersion, String currentVersion) {
        int[] current = parseVersion(currentVersion);
        if (current == null) {
            // 当前版本号自己都解析不出来：宁可不动用户文件
            return false;
        }
        int[] user = parseVersion(userVersion);
        if (user == null) {
            return true;
        }
        int length = Math.max(user.length, current.length);
        for (int i = 0; i < length; i++) {
            int left = i < user.length ? user[i] : 0;
            int right = i < current.length ? current[i] : 0;
            if (left != right) {
                return left < right;
            }
        }
        return false;
    }

    /** 解析成数字段：{@code "2.0.3"} -> {@code [2,0,3]}；纯整数老格式 {@code "9"} -> {@code [0,0,9]}；解析不了返回 null。 */
    private static int[] parseVersion(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        int cut = value.indexOf('-');
        if (cut >= 0) {
            value = value.substring(0, cut);
        }
        cut = value.indexOf('+');
        if (cut >= 0) {
            value = value.substring(0, cut);
        }
        if (value.isBlank()) {
            return null;
        }
        boolean legacyCounter = value.indexOf('.') < 0;
        String[] parts = value.split("\\.");
        int[] numbers = new int[legacyCounter ? 3 : parts.length];
        int offset = legacyCounter ? 2 : 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || !parts[i].chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                numbers[offset + i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return numbers;
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

    /**
     * 备份文件名形如 {@code config.json.v2.0.2.bak} / {@code config.json.v9.bak}（老整数版本）。
     * 版本号里的非法字符会被替换，避免造出奇怪的文件名。
     */
    private static void backup(Path file, String version) throws IOException {
        String label = version == null || version.isBlank()
                ? "unknown"
                : version.replaceAll("[^0-9A-Za-z._-]", "_");
        String name = file.getFileName() + ".v" + label + ".bak";
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
