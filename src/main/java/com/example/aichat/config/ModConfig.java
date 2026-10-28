package com.example.aichat.config;

/**
 * 与 config.json 字段一一对应。
 * api_url: 默认 DeepSeek 地址
 * api_key: 用户自己的 Key
 * model: 模型名
 * system_prompt_file: 系统提示词文件名（相对于 config/aichat/）
 * persona_prompt_file: 人设提示词文件名（相对于 config/aichat/）
 * reply_prefix: AI 广播消息的前缀，留空则不加前缀
 * max_context_messages: 保留最近多少条聊天上下文
 * request_timeout_seconds: 单次 HTTP 请求整体超时
 * enable_join_quit_events: 是否对加入/退出事件也触发 AI
 * knowledge_dir: 知识库目录名（相对于 config/aichat/）
 * use_json_mode: 是否发送 response_format=json_object
 * enable_thinking: 是否开启深度思考（thinking），默认关闭
 * enable_tools: 是否发送工具定义（知识库检索），默认关闭
 * api_type: auto / openai / deepseek / anthropic，auto 时按 api_url 判断
 * reasoning_effort: low/medium/high 等；留空则不发送
 * max_output_tokens: 最大输出 token；0 表示不发送（Anthropic 用默认值）
 * thinking_budget_tokens: Anthropic 思考预算
 * config_version: 配置文件版本，用于升级时自动补齐新字段
 * buffer_enabled: 是否启用消息缓冲（防抖合并，省 token）
 * buffer_delay_seconds: 最后一条消息后等待多少秒再发送
 * buffer_max_messages: 缓冲区最多条数，满了立即发送
 * buffer_max_chars: 缓冲区最多字符数，满了立即发送
 * system_prompt_in_user: 把系统提示词一并放进最后一条用户消息，默认 true
 * memory_enabled: 是否开启记忆功能（AI 可读写记忆文件），默认 true
 * memory_dir: 记忆文件夹名（相对于 config/aichat/）
 * memory_max_file_kb: 单个记忆文件上限（KB），超限由 AI 自行压缩
 * server_tools_*: 服务器根目录文件管理工具（默认关闭，只有带前缀的特权消息才能调用）
 */
public class ModConfig {
    public int config_version = 6;
    public String api_url = "https://api.deepseek.com/chat/completions";
    public String api_key = "";
    public String model = "deepseek-flash";
    public String system_prompt_file = "system_prompt.txt";
    public String persona_prompt_file = "persona_prompt.txt";
    public String reply_prefix = "[AI] ";
    public int max_context_messages = 10;
    public int request_timeout_seconds = 20;
    public boolean enable_join_quit_events = true;
    public String knowledge_dir = "knowledge";
    public boolean use_json_mode = true;
    public boolean enable_thinking = false;
    public boolean enable_tools = false;
    public String api_type = "auto";
    public String reasoning_effort = "";
    /**
     * 是否发送 thinking 字段，默认 false。
     * 部分平台（如千问 MaaS）收到关闭思考的参数后不再返回 tool_calls，
     * 需要工具调用时请保持 false。
     */
    public boolean send_thinking_field = false;
    public int max_output_tokens = 0;
    public int thinking_budget_tokens = 1024;
    /**
     * 是否允许模型调用 web_search 工具联网搜索。关闭时不注册该工具，行为与 1.6.2 一致。
     */
    public boolean search_enabled = true;
    /**
     * 搜索后端：bing（默认，抓 cn.bing.com，零 Key）/ searxng（自建实例）/ tavily / bocha / zhipu。
     */
    public String search_provider = "bing";
    /**
     * 搜索接口地址。留空使用 provider 的默认端点；searxng 必须填（如 http://127.0.0.1:8888）。
     */
    public String search_api_url = "";
    /** 商业搜索 API 的 Key；bing / searxng 留空即可。 */
    public String search_api_key = "";
    /** 一次搜索最多取几条结果，上限 10。 */
    public int search_max_results = 5;
    /** 搜索请求超时（秒），与对话请求超时相互独立。 */
    public int search_timeout_seconds = 10;
    /**
     * 是否开启记忆功能：AI 可以在记忆文件夹里读写纯文本笔记，跨对话保存。
     * 开启时会顺带打开工具通道——即使 enable_tools 为 false，也会发送记忆工具定义。
     * 默认开启，开箱即用；不想用（或要严格保持 1.7.0 的老行为）时设为 false。
     */
    public boolean memory_enabled = true;
    /** 记忆文件夹名（相对于 config/aichat/）。不填按 memory 处理。 */
    public String memory_dir = "memory";
    /** 单个记忆文件的字节上限（KB）。写入超过该值时被拒绝，由 AI 自己压缩后覆写。 */
    public int memory_max_file_kb = 8;
    public boolean buffer_enabled = true;
    public int buffer_delay_seconds = 5;
    public int buffer_max_messages = 10;
    public int buffer_max_chars = 500;
    /**
     * 是否把系统提示词（含 JSON 格式契约、知识库文件列表、人设）再拼进最后一条用户消息。
     * 部分中转站/兼容端点会把 system 角色整条丢弃，导致模组的提示词从未到达模型，
     * 表现为模型自由发挥、不输出 JSON、瞎猜知识库文件名。默认开启；接口确实支持
     * system 时可以设为 false，省一份重复的提示词 token。
     */
    public boolean system_prompt_in_user = true;

    // ---------------- 服务器根目录文件管理工具（2.0.0 新增） ----------------

    /**
     * 总开关，默认 false。开启后，聊天消息以 server_tools_prefix 开头的特权请求才允许调用
     * server_files 工具；普通聊天一律不注册该工具，模型即使硬编出名字也只会收到「未知工具」。
     */
    public boolean server_tools_enabled = false;
    /** 特权请求前缀。留空表示关闭前缀通道（工具永远不可用）。 */
    public String server_tools_prefix = "!+";
    /** 权限判定方式：op（原版权限等级）/ list（管理员名单文件）/ luckperms（权限节点）。 */
    public String server_tools_permission_mode = "op";
    /** op 模式下要求的原版权限等级：1/2/3/4，默认 4。 */
    public int server_tools_permission_level = 4;
    /** luckperms 模式下检查的权限节点。 */
    public String server_tools_luckperms_node = "aichat.servertools";
    /** list 模式的管理员名单文件（相对 config/aichat/）。 */
    public String server_tools_admins_file = "server_admins.json";
    /** 单次读取文件的字节上限（KB）。 */
    public int server_tools_read_max_kb = 48;
    /** 单次写入文件的字节上限（KB）。 */
    public int server_tools_write_max_kb = 64;
    /** 列目录返回的最大条目数。 */
    public int server_tools_list_max_entries = 200;
    /** 删除时是否移入 .aichat-trash/<时间戳>/ 回收目录。 */
    public boolean server_tools_trash_enabled = true;
    /** 禁止读写的路径（相对服务器根目录，目录连带其下所有内容）。 */
    public String[] server_tools_deny_paths = {
            "config/aichat/config.json",
            "config/aichat/system.json",
            "config/aichat/server_admins.json",
            ".aichat-trash"
    };
}
