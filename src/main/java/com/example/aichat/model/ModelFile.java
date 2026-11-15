package com.example.aichat.model;

/**
 * 单个模型目录下 {@code model.json} 的字段。全部可留空 / null，表示继承 config.json。
 * <p>
 * 用包装类型（Boolean）而不是 boolean：{@code null} 才代表「没配，跟随主配置」，
 * 布尔默认值会把「没写」和「写了 false」混成一件事。
 */
public class ModelFile {
    public String version = "";
    public String api_url = "";
    public String api_key = "";
    public String model = "";
    public String api_type = "auto";
    public String system_prompt_file = "system_prompt.txt";
    public String persona_prompt_file = "persona_prompt.txt";
    public String reply_prefix = "";
    public String self_name = "";
    public int max_output_tokens = 0;
    public int thinking_budget_tokens = 1024;
    public Boolean enable_thinking;
    public Boolean send_thinking_field;
    public String reasoning_effort = "";
    public Boolean use_json_mode;
    public Boolean enable_tools;
    public Boolean memory_enabled;
}
