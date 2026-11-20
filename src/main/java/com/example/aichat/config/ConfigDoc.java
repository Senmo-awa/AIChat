package com.example.aichat.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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

        /** 直接给原始 JSON 值（数组、对象、null）。 */
        static Field of(String key, String comment, JsonElement value) {
            return new Field(key, comment, value);
        }
    }

    /** 一个嵌套对象（system.json 里的 judge / reply）。 */
    public record Section(String key, String comment, List<Field> fields) {
    }

    private static final String CONFIG_HEADER = """
            // AI Chat 配置文件（config.json）
            // 以 // 开头的行都是注释，模组读取时会忽略，可以随手改、随手加。
            // 改完执行 /aichat reload 即可生效，不需要重启服务器。
            // config_version 写的是模组版本号（如 "2.3.0"）：升级模组后若它低于当前模组版本，
            // 本文件会被重写一次补齐新字段（原文件备份为 config.json.v<旧版本>.bak），
            // 你自己的注释只在这次重写时会被替换成模组内置的说明。
            """;

    private static final String SYSTEM_HEADER = """
            // AI Chat 模型编排配置（system.json）
            // 以 // 开头的行都是注释，模组读取时会忽略。
            // multi_model_enabled=true 时按下面的 models 列表加载多个模型：
            //   judge 只判断要不要接话，chat 负责回复（可以多个），admin 接管管理员特权请求。
            // 每个模型的具体连接参数在自己的 config/aichat/models/<id>/model.json 里，留空即继承 config.json。
            // 旧的 dual_model_enabled + judge / reply 已弃用，设成 true 仍按老逻辑工作。
            """;

    /** config.json 的字段顺序、默认值与注释。 */
    public static final List<Object> CONFIG_ENTRIES = List.<Object>of(
            Field.of("config_version",
                    "配置文件版本，写的是模组版本号（如 \"2.3.0\"）。升级模组后这个值会跟模组版本比对，低于当前版本就补齐新字段并重写一次本文件。不要手动改大，否则升级时不会再补字段。",
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
            Field.of("blocked_message_patterns",
                    "【屏蔽规则 · 消息】对聊天内容做正则匹配，命中任一条就不送 AI：不进缓冲和历史，也不计 token。"
                            + ConfigManager.BLOCK_NOTICE
                            + "默认 ^\\s*!! 用于挡住 !! 开头的指令调用；空数组表示不屏蔽任何消息。"
                            + "匹配方式为任意位置匹配，不区分大小写（写 (?-i) 可关闭），需要从头匹配时自己写 ^。",
                    ConfigManager.DEFAULT_MESSAGE_PATTERNS),
            Field.of("blocked_player_patterns",
                    "【屏蔽规则 · 玩家】对发送者名字做正则匹配，命中后其聊天与加入/退出事件一律不送 AI。"
                            + ConfigManager.BLOCK_NOTICE
                            + "默认 ^bot_ 用于挡住 carpet 假人；空数组表示不屏蔽任何玩家。"
                            + "与消息规则互相独立：这个看「谁说的」，上面那个看「说了什么」。",
                    ConfigManager.DEFAULT_PLAYER_PATTERNS),

            Field.of("tool_result_budget_kb",
                    "单次对话所有工具结果回灌给模型的总预算（KB），0 = 不限。一次对话可能调用 4 轮工具，每轮都会把之前的结果重新发一遍；预算给小了省钱、给大了能读更多内容，默认 24。",
                    24),
            Field.of("fallback_reply",
                    "工具轮次用尽或模型没给出合法 JSON 时，代替沉默播报的一句话（留空 = 保持静默）。例如模型输出了私有工具标记导致解析失败，玩家至少能看到这句。",
                    "（这轮没理出结果，请再说一次）"),

            Field.of("player_cooldown_seconds",
                    "【玩家冷却】同一玩家两条消息的最小间隔（秒），0 = 关闭（默认）。冷却期内的消息直接丢弃：不进缓冲、不发请求。防刷屏式提问。",
                    0),
            Field.of("daily_token_budget",
                    "【每日预算】全局每天可用 token 上限，0 = 不限（默认）。用尽后暂停一切 AI 请求（含 join/quit），跨天自动恢复，告警每小时最多一条。",
                    0),
            Field.of("reply_only_when_mentioned",
                    "【被动回复】true = 只有聊天里出现 mention_names 的任一词才接话，其余消息零 token 忽略。默认 false（所有消息都走原有判断）。",
                    false),
            Field.of("mention_names",
                    "被动回复的点名关键词（大小写不敏感，可带 @）。默认 [\"小墨\"]。",
                    List.of("小墨")),
            Field.of("redact_secrets",
                    "广播前把疑似密钥的内容（sk-xxx / Bearer xxx / password=xxx）替换成 ***。默认开，防 AI 把读到的密码或 Key 说出来。",
                    true),
            Field.of("private_chat_mode",
                    "【私聊】/aichat pm <内容> 的可用范围：off 关闭 / admin 仅管理员（默认）/ all 所有人。"
                            + "管理员沿用下面 server_tools_permission_mode 的判定方式（op 等级、管理员名单或 LuckPerms 节点），不另立名单。"
                            + "私聊回复只发给发起者本人，不进公共历史；token 单独统计，/aichat token 里分开显示。",
                    "admin"),

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
                    "配置文件版本，与 config.json 的 config_version 用同一个模组版本号（如 \"2.3.0\"）。",
                    ConfigManager.CURRENT_SYSTEM_VERSION),
            Field.of("multi_model_enabled",
                    "【多模型总开关】true 时按下面的 models 列表加载多个模型：judge 判断要不要接话，chat 负责回复（可以多个），admin 接管管理员特权请求。"
                            + "每个模型在自己目录里的 model.json 单独配置，api_key 留空就继承 config.json 的那把。",
                    false),
            Field.of("models_dir",
                    "模型配置目录的根（相对 config/aichat/）。每个模型在它下面占一个子目录，第一次启用时模组会自动建目录并写入默认 model.json。",
                    "models"),
            Field.of("ai_dialogue_max_rounds",
                    "模型之间自动对话的最大轮数，0 = 关闭。达到上限的最后一轮会在提问里注入「此轮为最终轮」的提示，让对方知道不会再被接话。",
                    2),
            Field.of("models",
                    "【模型列表】顺序即发言顺序。id 全局唯一，也是 token 统计里的分类名；role 取 judge / chat / admin；"
                            + "dir 是相对 models_dir 的目录（留空用 id）；routes_to 只对 judge 有效（留空 = 所有 chat 模型）；"
                            + "reply_to_ai 只对 chat 有效（是否参与 AI 互相对话）。",
                    defaultModels()),
            Field.of("dual_model_enabled",
                    "【已弃用】旧的双模型开关：judge 判断、reply 回复。2.2.0 起推荐用上面的多模型；这里设为 true 仍按老逻辑工作。",
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

    private static final String MODEL_HEADER = """
            // AI Chat 单个模型的配置（model.json）
            // 这里的每个字段留空都表示「继承 config.json」：只有一个 Key、一个接口时不用在这里重复填。
            // 想给某个模型单独用别的 Key / 别的模型名，就在本文件里写死。
            // 布尔字段可以写 true / false 覆盖，写 null 表示继承。
            """;

    /** 单个模型 model.json 的字段顺序、默认值与注释。 */
    public static final List<Object> MODEL_ENTRIES = List.<Object>of(
            Field.of("version", "配置文件版本（模组版本号）。由模组维护，一般不用改。",
                    ConfigManager.CURRENT_SYSTEM_VERSION),
            Field.of("api_url", "对话接口地址。留空继承 config.json 的 api_url。", ""),
            Field.of("api_key", "API Key。留空继承 config.json 的 api_key；多个模型共用一个 Key 时不必重复填。", ""),
            Field.of("model", "模型名（如 deepseek-flash / qwen-plus）。留空继承 config.json 的 model。", ""),
            Field.of("api_type", "协议类型：auto（按地址猜）/ openai / deepseek / anthropic。", "auto"),
            Field.of("system_prompt_file",
                    "系统提示词文件名：先在本模型目录里找，找不到就用 config/aichat/ 下的同名文件。",
                    "system_prompt.txt"),
            Field.of("persona_prompt_file", "人设文件名，查找规则同上。", "persona_prompt.txt"),
            Field.of("reply_prefix",
                    "这个模型回复的前缀，留空用 config.json 的 reply_prefix。启用多个聊天模型时，靠前缀一眼区分是谁在说话。",
                    ""),
            Field.of("self_name", "模型之间互相对话时显示的名字，留空用模型 id。", ""),
            Field.of("max_output_tokens", "最大输出 token，0 = 继承 config.json。", 0),
            Field.of("thinking_budget_tokens", "Anthropic 的思考预算 token。", 1024),
            Field.of("enable_thinking", "是否开启深度思考。null = 继承 config.json，true / false 单独覆盖。",
                    JsonNull.INSTANCE),
            Field.of("send_thinking_field", "是否发送 thinking 字段。null = 继承 config.json。",
                    JsonNull.INSTANCE),
            Field.of("reasoning_effort", "推理强度：low / medium / high；留空继承 config.json。", ""),
            Field.of("use_json_mode", "是否发送 response_format=json_object。null = 继承 config.json。",
                    JsonNull.INSTANCE),
            Field.of("enable_tools", "是否允许本模型使用工具（知识库 / 搜索）。null = 继承 config.json。",
                    JsonNull.INSTANCE),
            Field.of("memory_enabled", "是否允许本模型读写记忆。null = 继承 config.json。",
                    JsonNull.INSTANCE)
    );

    /** 默认的三个模型：判断 / 聊天 / 管理，全部继承 config.json 的连接参数。 */
    private static JsonArray defaultModels() {
        JsonArray models = new JsonArray();
        models.add(modelEntry("judge", "judge", false));
        models.add(modelEntry("chat-main", "chat", true));
        models.add(modelEntry("admin", "admin", false));
        return models;
    }

    private static JsonObject modelEntry(String id, String role, boolean replyToAi) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("role", role);
        object.addProperty("enabled", true);
        object.addProperty("dir", "");
        object.add("routes_to", new JsonArray());
        object.addProperty("reply_to_ai", replyToAi);
        return object;
    }

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

    public static String renderModel(JsonObject values) {
        return render(MODEL_HEADER, MODEL_ENTRIES, values);
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

    /** 值渲染：字符串加引号转义，数组/对象多行缩进输出。 */
    public static String format(JsonElement value) {
        return format(value, "  ");
    }

    private static String format(JsonElement value, String indent) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            return primitive.isString() ? quote(primitive.getAsString()) : primitive.getAsString();
        }
        String inner = indent + "  ";
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.isEmpty()) {
                return "[]";
            }
            boolean complex = false;
            List<String> parts = new ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject() || element.isJsonArray()) {
                    complex = true;
                }
                parts.add(format(element, inner));
            }
            if (!complex && parts.size() == 1) {
                return "[" + parts.get(0) + "]";
            }
            StringBuilder multi = new StringBuilder("[\n");
            for (int i = 0; i < parts.size(); i++) {
                multi.append(inner).append(parts.get(i));
                if (i < parts.size() - 1) {
                    multi.append(',');
                }
                multi.append('\n');
            }
            multi.append(indent).append("]");
            return multi.toString();
        }
        JsonObject object = value.getAsJsonObject();
        if (object.isEmpty()) {
            return "{}";
        }
        StringBuilder block = new StringBuilder("{\n");
        int index = 0;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            block.append(inner).append(quote(entry.getKey())).append(": ")
                    .append(format(entry.getValue(), inner));
            if (++index < object.size()) {
                block.append(',');
            }
            block.append('\n');
        }
        block.append(indent).append("}");
        return block.toString();
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
