package com.example.aichat.config;

/**
 * 与 config.json 字段一一对应。
 * api_url: 默认 DeepSeek 地址
 * api_key: 用户自己的 Key
 * model: 模型名
 * system_prompt_file: 系统提示词文件名（相对于 config/aichat/）
 * persona_prompt: 玩家设定的人设提示词
 * max_context_messages: 保留最近多少条聊天上下文
 * request_timeout_seconds: 单次 HTTP 请求整体超时
 * enable_join_quit_events: 是否对加入/退出事件也触发 AI
 * knowledge_dir: 知识库目录名（相对于 config/aichat/）
 * use_json_mode: 是否强制 response_format=json_object（若与工具调用不兼容可改为 false）
 */
public class ModConfig {
    public String api_url = "https://api.deepseek.com/chat/completions";
    public String api_key = "";
    public String model = "deepseek-flash";
    public String system_prompt_file = "system_prompt.txt";
    public String persona_prompt = "";
    public int max_context_messages = 10;
    public int request_timeout_seconds = 20;
    public boolean enable_join_quit_events = true;
    public String knowledge_dir = "knowledge";
    public boolean use_json_mode = true;
}
