package com.example.aichat.model;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigDoc;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.example.aichat.config.SystemConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多模型注册表：把 {@code system.json} 的 models 列表 + 每个模型目录里的 {@code model.json}
 * 合并成可直接运行的 {@link ModelSpec}。
 * <p>
 * 第一次遇到没有目录 / 没有 model.json 的模型时，自动建目录并写一份带注释的默认配置——
 * 主人只要在 system.json 里写个 id 然后 reload，就能拿到一份可以马上改的文件。
 * 已经存在的 model.json 一律不动：里面的值和主人加的注释都属于主人。
 */
public final class ModelRegistry {
    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();
    private static volatile List<ModelSpec> MODELS = List.of();

    private ModelRegistry() {
    }

    /** 读取 system.json 的 models 列表；multi_model_enabled=false 时清空。 */
    public static void reload() {
        SystemConfig system = ConfigManager.system();
        if (system == null || !system.multi_model_enabled) {
            synchronized (LOCK) {
                MODELS = List.of();
            }
            return;
        }
        Path root = ConfigManager.dir().resolve(or(system.models_dir, "models"));
        List<ModelSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SystemConfig.ModelEntry entry : system.models) {
            if (entry == null || entry.id == null || entry.id.isBlank() || !entry.enabled) {
                continue;
            }
            String id = entry.id.trim();
            if (!seen.add(id.toLowerCase(Locale.ROOT))) {
                AiChatMod.LOGGER.warn("多模型：模型 id 重复（{}），只保留第一个", id);
                continue;
            }
            String dirName = entry.dir == null || entry.dir.isBlank() ? id : entry.dir.trim();
            Path dir = root.resolve(dirName).normalize();
            try {
                Files.createDirectories(dir);
                ModelFile file = loadOrCreate(dir);
                specs.add(build(id, entry, dir, file));
            } catch (IOException | RuntimeException e) {
                AiChatMod.LOGGER.warn("多模型：模型 {} 加载失败（{}），已跳过", id, String.valueOf(e.getMessage()));
            }
        }
        synchronized (LOCK) {
            MODELS = List.copyOf(specs);
        }
        if (specs.isEmpty()) {
            AiChatMod.LOGGER.warn("多模型：multi_model_enabled=true 但一个可用模型都没有，回落到单模型模式");
        } else {
            AiChatMod.LOGGER.info("多模型：已加载 {} 个模型（{}）", specs.size(), summarize(specs));
        }
    }

    private static String summarize(List<ModelSpec> specs) {
        StringBuilder text = new StringBuilder();
        for (ModelSpec spec : specs) {
            if (!text.isEmpty()) {
                text.append("，");
            }
            text.append(spec.id()).append('=').append(spec.role().label())
                    .append('/').append(spec.model());
        }
        return text.toString();
    }

    /** 读 model.json；不存在就写一份默认的。 */
    private static ModelFile loadOrCreate(Path dir) throws IOException {
        Path file = dir.resolve("model.json");
        if (!Files.exists(file)) {
            Files.writeString(file, ConfigDoc.renderModel(new JsonObject()), StandardCharsets.UTF_8);
            AiChatMod.LOGGER.info("多模型：已生成默认配置 {}", file);
            return new ModelFile();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        ModelFile parsed = GSON.fromJson(root, ModelFile.class);
        return parsed == null ? new ModelFile() : parsed;
    }

    private static ModelSpec build(String id, SystemConfig.ModelEntry entry, Path dir, ModelFile file) {
        ModConfig main = ConfigManager.get();
        ModelSpec.Role role = ModelSpec.Role.parse(entry.role);
        boolean judge = role == ModelSpec.Role.JUDGE;

        String apiUrl = or(file.api_url, main.api_url);
        String apiKey = or(file.api_key, main.api_key);
        String model = or(file.model, main.model);
        String apiType = or(file.api_type, main.api_type);
        String promptFile = or(file.system_prompt_file, "system_prompt.txt");
        String personaFile = or(file.persona_prompt_file, main.persona_prompt_file);
        String prefix = file.reply_prefix == null || file.reply_prefix.isEmpty()
                ? main.reply_prefix
                : file.reply_prefix;
        String selfName = file.self_name == null ? "" : file.self_name.trim();
        String effort = file.reasoning_effort == null || file.reasoning_effort.isBlank()
                ? main.reasoning_effort
                : file.reasoning_effort.trim();

        // 判断模型默认关思考：推理模型的思考会吃光输出预算，content 变空 → 永远判成「不回复」
        boolean thinking = file.enable_thinking != null
                ? file.enable_thinking
                : (judge ? false : main.enable_thinking);
        boolean sendThinking = file.send_thinking_field != null
                ? file.send_thinking_field
                : main.send_thinking_field;
        boolean jsonMode = file.use_json_mode != null ? file.use_json_mode : main.use_json_mode;
        boolean tools = file.enable_tools != null ? file.enable_tools : main.enable_tools;
        boolean memory = file.memory_enabled != null ? file.memory_enabled : main.memory_enabled;
        int maxOutput = file.max_output_tokens > 0
                ? file.max_output_tokens
                : (judge ? 1024 : Math.max(0, main.max_output_tokens));
        int budget = file.thinking_budget_tokens > 0
                ? file.thinking_budget_tokens
                : main.thinking_budget_tokens;

        List<String> routes = entry.routes_to == null ? List.of() : List.copyOf(entry.routes_to);
        return new ModelSpec(
                id, role, true, dir,
                apiUrl, apiKey, model, apiType,
                promptFile, personaFile, prefix, selfName,
                maxOutput, budget, thinking, sendThinking, effort,
                jsonMode, tools, memory, routes, entry.reply_to_ai
        );
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static List<ModelSpec> all() {
        return MODELS;
    }

    /** 多模型是否真的可用（开关打开且至少加载到一个模型）。 */
    public static boolean active() {
        return !MODELS.isEmpty();
    }

    public static List<ModelSpec> byRole(ModelSpec.Role role) {
        List<ModelSpec> result = new ArrayList<>();
        for (ModelSpec spec : MODELS) {
            if (spec.role() == role) {
                result.add(spec);
            }
        }
        return result;
    }

    public static ModelSpec byId(String id) {
        if (id == null) {
            return null;
        }
        for (ModelSpec spec : MODELS) {
            if (spec.id().equalsIgnoreCase(id.trim())) {
                return spec;
            }
        }
        return null;
    }
}
