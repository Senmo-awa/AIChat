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
 */
public class ModConfig {
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
}
