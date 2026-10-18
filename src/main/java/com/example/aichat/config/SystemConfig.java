package com.example.aichat.config;

/**
 * system.json：双模型模式的独立 API 配置。
 * judge 只判断是否回复，reply 负责生成内容。
 * 字段留空时回退到 config.json 的同名配置。
 */
public class SystemConfig {
    public int version = 1;
    public boolean dual_model_enabled = false;
    public ApiSettings judge = new ApiSettings();
    public ApiSettings reply = new ApiSettings();

    public static class ApiSettings {
        public String api_url = "";
        public String api_key = "";
        public String model = "";
        public String api_type = "auto";
        public String system_prompt_file = "";
        public int max_output_tokens = 0;
        public int thinking_budget_tokens = 1024;
    }
}
