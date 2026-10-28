package com.example.aichat.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config.json / system.json 的「带注释」文档模型。
 * <p>
 * JSON 标准不支持注释，但 Gson 的宽松模式（{@code JsonParser.parseString} 内部就是宽松读法）会跳过
 * {@code //} 与 {@code /* *}{@code /} 注释，所以配置文件里可以安全地写字注释：读的时候忽略，写的时候由
 * 这个类按字段顺序重新渲染出来。
 * <p>
 * 分工：
 * <ul>
 *   <li>{@link #CONFIG_ENTRIES} / {@link #SYSTEM_ENTRIES} —— 字段顺序、默认值、每个字段的说明；
 *       这是「配置文件长什么样」的唯一事实来源（不再维护 JSON 模板资源，避免两边不同步）。</li>
 *   <li>{@link #defaults} —— 升级时用来补齐缺失字段。</li>
 *   <li>{@link #render} —— 把「用户的当前值」按顺序渲染成带注释的文本。</li>
 * </ul>
 * 升级只会在版本变化时重写文件；平时用户自己加的注释、空行、字段顺序都不会被动。
 */
public final class ConfigDoc {
    private ConfigDoc() {
    }

    /** 一个普通字段：键名 + 注释（可多行，null 表示不加注释）+ 默认值。 */
    public record Field(String key, String comment, JsonElement value) {
        static Field of(String key, String comment, String value) {
            return new Field(key, comment, new JsonPrimitive(value));
        }

        static Field of(String key, String comment, boolean value) {
            return new Field(key, comment, new JsonPrimitive(value));
        }

        static Field of(String key, String comment, int value) {
            return new Field(key, comment, new JsonPrimitive(value));
        }

        static Field of(String key, String comment, List<String> values) {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            return new Field(key, comment, array);
        }
    }

    /** 一个嵌套对象（system.json 里的 judge / reply）。 */
    public record Section(String key, String comment, List<Field> fields) {
    }

    private static final String CONFIG_HEADER = """
            // AI Chat 配置文件（config.json）
            // 以 // 开头的行都是注释，模组读取时会忽略，可以随手改、随手加。
            // 改完执行 /aichat reload 即可生效，不需要重启服务器。
            // 升级模组时若 config_version 变高，本文件会被重写一次（原文件备份为 config.json.v<旧版本>.bak），
            // 你自己的注释只在这次重写时会被替换成模组内置的说明。
            """;

    private static final String SYSTEM_HEADER = """
            // AI Chat 双模型配置（system.json）
            // 以 // 开头的行都是注释，模组读取时会忽略。
            // dual_model_enabled=true 时，judge 判断要不要接话、reply 负责写回复；留空字段会回落到 config.json。
            """;

    /** config.json 的字段顺序、默认值与注释。 */
    public static final List<Object> CONFIG_ENTRIES = List.<Object>of(
            Field.of("config_version",
                    "配置文件版本，模组用它判断是否需要补新字段。不要手动改小，否则会被当成旧文件重写。",
                    ConfigManager.CURRENT_CONFIG_VERSION),

            Field.of("api_url",
                    "对话接口地址。可填完整端点（.../chat/completions），也可只填 base URL（如 https://xxx/v1），模组会自动补全。",
                    "https://api.deepseek.com/chat/completions"),
            Field.of("api_key",
                    "你的 API Key。保留占位值时模组不发送请求（本地 Ollama / LM Studio 这类不需要 Key 的服务可留空）。",
                    "sk-your-key-here"),
            Field.of("model",
                    "模型名，例如 deepseek-chat / qwen-plus / glm-4.6 / claude-3-5-sonnet。",
                    "deepseek-flash"),
            Field.of("api_type",
                    "协议类型：auto（按 api_url 猜）/ openai / deepseek / anthropic。",
                    "auto"),

            Field.of("system_prompt_file",
                    "系统提示词文件名（相对 config/aichat/），单模型模式使用。",
                    "system_prompt.txt"),
            Field.of("persona_prompt_file",
                    "人设提示词文件名（相对 config/aichat/），只影响语气和角色，不覆盖行为规则。",
                    "persona_prompt.txt"),
            Field.of("reply_prefix",
                    "AI 广播消息的前缀。设为空字符串则不加前缀。",
                    "[AI] "),
            Field.of("max_context_messages",
                    "随请求发送的最近对话条数（玩家事件与 AI 回复都算一条）。",
                    10),
            Field.of("request_timeout_seconds",
                    "单次 HTTP 请求的整体超时（秒）。",
                    20),
            Field.of("enable_join_quit_events",
                    "是否让加入/退出事件也触发 AI（关闭可省 token）。",
                    true),
            Field.of("knowledge_dir",
                    "知识库目录名（相对 config/aichat/），里面的 .md/.txt 供 AI 按需读取。",
                    "knowledge"),

            Field.of("enable_tools",
                    "是否启用「知识库检索 + 联网搜索」这两个工具（默认关闭，省 token）。",
                    false),
            Field.of("use_json_mode",
                    "是否发送 response_format=json_object。启用工具时自动不发送。",
                    true),
            Field.of("enable_thinking",
                    "是否开启深度思考（thinking），默认关闭。",
                    false),
            Field.of("send_thinking_field",
                    "是否发送 thinking 字段。部分平台（如千问 MaaS）收到关闭思考的参数后不再返回 tool_calls，需要工具时保持 false。",
                    false),
            Field.of("reasoning_effort",
                    "推理强度：low / medium / high；留空则不发送。",
                    ""),
            Field.of("max_output_tokens",
                    "最大输出 token。0 表示不发送（Anthropic 用默认值）。推理模型的思考 token 也算在预算里。",
                    0),
            Field.of("thinking_budget_tokens",
                    "Anthropic 的思考预算 token。",
                    1024),
            Field.of("system_prompt_in_user",
                    "是否把系统提示词再拼进最后一条用户消息。部分中转站会整条丢弃 system 角色，开启可兜底；接口正常时可关闭省 token。",
                    true),

            Field.of("search_enabled",
                    "是否允许 AI 调用 web_search 联网搜索。关闭时不注册该工具。",
                    true),
            Field.of("search_provider",
                    "搜索后端：bing（默认，抓 cn.bing.com，零 Key）/ searxng（自建）/ tavily / bocha / zhipu。",
                    "bing"),
            Field.of("search_api_url",
                    "搜索接口地址。留空用 provider 默认端点；searxng 必须填（如 http://127.0.0.1:8888）。",
                    ""),
            Field.of("search_api_key",
                    "商业搜索 API 的 Key；bing / searxng 留空即可。",
                    ""),
            Field.of("search_max_results",
                    "一次搜索最多取几条结果（上限 10）。",
                    5),
            Field.of("search_timeout_seconds",
                    "搜索请求超时（秒），与对话请求超时互相独立。",
                    10),

            Field.of("memory_enabled",
                    "是否开启记忆功能：AI 可以在记忆目录读写纯文本笔记，跨对话保存。开启时会顺带打开工具通道。",
                    true),
            Field.of("memory_dir",
                    "记忆目录名（相对 config/aichat/）。",
                    "memory"),
            Field.of("memory_max_file_kb",
                    "单个记忆文件上限（KB）。写入超限会被拒绝，由 AI 自己压缩后覆写。",
                    8),

            Field.of("buffer_enabled",
                    "是否启用消息缓冲（防抖合并，省 token）。",
                    true),
            Field.of("buffer_delay_seconds",
                    "最后一条消息后等待多少秒再发送。",
                    5),
            Field.of("buffer_max_messages",
                    "缓冲区最多攒多少条，满了立即发送。",
                    10),
            Field.of("buffer_max_chars",
                    "缓冲区最多攒多少字符，满了立即发送。",
                    500),

            Field.of("server_tools_enabled",
                    "【服务器管理工具总开关】默认 false。开启后，拥有权限的玩家在聊天里用前缀发起请求，AI 才能读写服务器根目录（查看/新建/修改/删除文件）。",
                    false),
            Field.of("server_tools_prefix",
                    "特权请求前缀：只有以它开头的聊天消息才允许调用服务器管理工具（例如 \"!+ 看一下 mods 目录\"）。留空表示关闭前缀通道。",
                    "!+"),
            Field.of("server_tools_permission_mode",
                    "权限判定方式：op（按原版权限等级）/ list（按下面的管理员名单文件）/ luckperms（查 LuckPerms 权限节点）。",
                    "op"),
            Field.of("server_tools_permission_level",
                    "op 模式下要求的原版权限等级：1=管理员 2=游戏管理员 3=op 4=服主（默认 4，即 op 满级）。",
                    4),
            Field.of("server_tools_luckperms_node",
                    "luckperms 模式下查询的权限节点；被授予该节点的玩家（或组）才能调用工具。",
                    "aichat.servertools"),
            Field.of("server_tools_admins_file",
                    "list 模式使用的管理员名单文件名（相对 config/aichat/），可用 /aichat tools add|remove <玩家> 维护。",
                    "server_admins.json"),
            Field.of("server_tools_read_max_kb",
                    "单次读取文件的最大字节数（KB）。超过会被截断，AI 需要分段读。",
                    48),
            Field.of("server_tools_write_max_kb",
                    "单次写入文件的最大字节数（KB），防止 AI 一口气写坏大文件。",
                    64),
            Field.of("server_tools_list_max_entries",
                    "列目录时最多返回多少项（超出会提示用 op=search 缩小范围）。",
                    200),
            Field.of("server_tools_trash_enabled",
                    "删除时是否移入回收目录 .aichat-trash/<时间戳>/ 而不是直接抹掉。强烈建议保持 true。",
                    true),
            Field.of("server_tools_broadcast",
                    "特权消息要不要广播给其他玩家：show 原样广播（默认）/ hide 不广播（只给发送者、管理员和控制台）/ show_by_flag 默认不广播，末尾加标志位才广播 / hide_by_flag 默认广播，末尾加标志位就不广播。",
                    "show"),
            Field.of("server_tools_broadcast_flag",
                    "上面 show_by_flag / hide_by_flag 用的消息尾缀，默认 -f（消息末尾写 -f 表示切换广播行为，这个尾缀不会发给 AI）。",
                    "-f"),
            Field.of("server_tools_deny_paths",
                    "禁止 AI 读写的路径（相对服务器根目录，目录会连带禁止其下所有内容）。默认挡住本模组自己的配置（里面有 API Key）。",
                    List.of("config/aichat/config.json",
                            "config/aichat/system.json",
                            "config/aichat/server_admins.json",
                            ".aichat-trash"))
    );

    /** system.json 的字段顺序、默认值与注释。 */
    public static final List<Object> SYSTEM_ENTRIES = List.<Object>of(
            Field.of("version",
                    "配置文件版本，模组用它判断是否需要补新字段。",
                    ConfigManager.CURRENT_SYSTEM_VERSION),
            Field.of("dual_model_enabled",
                    "是否启用双模型模式：judge 只判断要不要接话，reply 只负责写回复。留空的字段会回落到 config.json 里的同名配置。",
                    false),
            new Section("judge",
                    "判断模型：只输出 {\"action\":\"reply\"} 或 {\"action\":\"ignore\"}，建议用小模型省钱。",
                    List.of(
                            Field.of("api_url", "留空则用 config.json 的 api_url。", ""),
                            Field.of("api_key", "留空则用 config.json 的 api_key。", ""),
                            Field.of("model", "留空则用 config.json 的 model。", ""),
                            Field.of("api_type", "auto / openai / deepseek / anthropic。", "auto"),
                            Field.of("system_prompt_file", "判断用的提示词文件。", "judge_prompt.txt"),
                            Field.of("max_output_tokens", "判断输出预算。推理模型建议 1024 以上，否则思考会吃光预算导致空输出。", 0),
                            Field.of("thinking_budget_tokens", "Anthropic 思考预算。", 1024))),
            new Section("reply",
                    "回复模型：真正写聊天内容。",
                    List.of(
                            Field.of("api_url", "留空则用 config.json 的 api_url。", ""),
                            Field.of("api_key", "留空则用 config.json 的 api_key。", ""),
                            Field.of("model", "留空则用 config.json 的 model。", ""),
                            Field.of("api_type", "auto / openai / deepseek / anthropic。", "auto"),
                            Field.of("system_prompt_file", "回复用的提示词文件。", "reply_prompt.txt"),
                            Field.of("max_output_tokens", "回复输出预算，0 表示用 config.json 的值。", 0),
                            Field.of("thinking_budget_tokens", "Anthropic 思考预算。", 1024)))
    );

    /** 按字段表生成默认值对象（升级时用来补齐缺失字段）。 */
    public static JsonObject defaults(List<?> entries) {
        JsonObject result = new JsonObject();
        for (Object entry : entries) {
            if (entry instanceof Field field) {
                result.add(field.key(), field.value().deepCopy());
            } else if (entry instanceof Section section) {
                JsonObject nested = new JsonObject();
                for (Field field : section.fields()) {
                    nested.add(field.key(), field.value().deepCopy());
                }
                result.add(section.key(), nested);
            }
        }
        return result;
    }

    /**
     * 把「当前实际生效的值」渲染成带注释的 JSON 文本。
     * 顺序、注释来自字段表；值来自 {@code values}（用户已有的优先）；字段表里没有的键原样保留在末尾。
     */
    public static String render(String header, List<?> entries, JsonObject values) {
        JsonObject source = values == null ? new JsonObject() : values;
        Set<String> emitted = new LinkedHashSet<>();
        List<String> blocks = new ArrayList<>();

        for (Object entry : entries) {
            if (entry instanceof Field field) {
                emitted.add(field.key());
                blocks.add(renderField("  ", field.key(), field.comment(), source.get(field.key()), field.value()));
            } else if (entry instanceof Section section) {
                emitted.add(section.key());
                blocks.add(renderSection(section, source.get(section.key())));
            }
        }

        List<String> extras = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (emitted.contains(entry.getKey())) {
                continue;
            }
            extras.add("  // 未识别的字段（模组不认识，原样保留）\n  "
                    + quote(entry.getKey()) + ": " + format(entry.getValue()));
        }
        blocks.addAll(extras);

        StringBuilder text = new StringBuilder();
        if (header != null && !header.isBlank()) {
            text.append(header.stripTrailing()).append('\n');
        }
        text.append("{\n");
        text.append(String.join(",\n", blocks));
        if (!blocks.isEmpty()) {
            text.append('\n');
        }
        text.append("}\n");
        return text.toString();
    }

    public static String renderConfig(JsonObject values) {
        return render(CONFIG_HEADER, CONFIG_ENTRIES, values);
    }

    public static String renderSystem(JsonObject values) {
        return render(SYSTEM_HEADER, SYSTEM_ENTRIES, values);
    }

    private static String renderField(String indent, String key, String comment,
                                      JsonElement value, JsonElement fallback) {
        StringBuilder block = new StringBuilder();
        appendComment(block, indent, comment);
        JsonElement effective = value == null ? fallback : value;
        block.append(indent).append(quote(key)).append(": ").append(format(effective));
        return block.toString();
    }

    private static String renderSection(Section section, JsonElement value) {
        JsonObject inner = value != null && value.isJsonObject()
                ? value.getAsJsonObject()
                : defaultsOf(section);
        List<String> blocks = new ArrayList<>();
        for (Field field : section.fields()) {
            blocks.add(renderField("    ", field.key(), field.comment(),
                    inner == null ? null : inner.get(field.key()), field.value()));
        }
        StringBuilder block = new StringBuilder();
        appendComment(block, "  ", section.comment());
        block.append("  ").append(quote(section.key())).append(": {\n");
        block.append(String.join(",\n", blocks));
        block.append("\n  }");
        return block.toString();
    }

    /** 单个嵌套对象的默认值（渲染时兜底用）。 */
    private static JsonObject defaultsOf(Section section) {
        JsonObject nested = new JsonObject();
        for (Field field : section.fields()) {
            nested.add(field.key(), field.value().deepCopy());
        }
        return nested;
    }

    private static void appendComment(StringBuilder block, String indent, String comment) {
        if (comment == null || comment.isBlank()) {
            return;
        }
        for (String line : comment.split("\n")) {
            block.append(indent).append("// ").append(line.strip()).append('\n');
        }
    }

    /** 值渲染：字符串加引号转义，数组逐项、单行输出，嵌套对象兜底用紧凑 JSON。 */
    public static String format(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            return primitive.isString() ? quote(primitive.getAsString()) : primitive.getAsString();
        }
        if (value.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                parts.add(format(element));
            }
            if (parts.size() > 1) {
                // 长数组（如路径黑名单）换行更好读
                StringBuilder multi = new StringBuilder("[\n");
                for (int i = 0; i < parts.size(); i++) {
                    multi.append("    ").append(parts.get(i));
                    if (i < parts.size() - 1) {
                        multi.append(',');
                    }
                    multi.append('\n');
                }
                multi.append("  ]");
                return multi.toString();
            }
            return "[" + String.join(", ", parts) + "]";
        }
        return value.toString();
    }

    private static String quote(String raw) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
