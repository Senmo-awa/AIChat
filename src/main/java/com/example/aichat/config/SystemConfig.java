package com.example.aichat.config;

import java.util.ArrayList;
import java.util.List;

/**
 * system.json：模型编排配置。
 * <p>
 * 两代模式并存：
 * <ul>
 *   <li><b>多模型（2.2.0，推荐）</b>：{@code multi_model_enabled=true} 时按 {@link #models} 列表加载，
 *       每个模型有自己的配置目录，角色分 judge / chat / admin；</li>
 *   <li><b>双模型（2.0.x，已弃用）</b>：{@code dual_model_enabled=true} 时用 {@link #judge} 判断、
 *       {@link #reply} 回复。新配置请用多模型，旧配置照旧能跑。</li>
 * </ul>
 * 字段留空时回退到 config.json 的同名配置。
 */
public class SystemConfig {
    /** 配置文件版本，与 config.json 的 config_version 用同一个版本号（如 {@code "2.2.0"}）。 */
    public String version = "";

    // ---------------- 多模型（2.2.0） ----------------

    /** 是否启用多模型编排。false 时回落到单模型（或旧的双模型）行为。 */
    public boolean multi_model_enabled = false;
    /** 模型配置目录的根（相对 config/aichat/）；每个模型在它下面占一个子目录。 */
    public String models_dir = "models";
    /** 模型之间自动对话的最大轮数（0 = 关闭 AI 互相对话）。 */
    public int ai_dialogue_max_rounds = 2;
    /** 模型列表。顺序即发言顺序。 */
    public List<ModelEntry> models = new ArrayList<>();

    /**
     * 一个模型的编排信息：它是什么角色、配置放哪、判断模型要路由给谁。
     * 真正的连接参数（api_url / api_key / model 等）在各模型的 {@code model.json} 里。
     */
    public static class ModelEntry {
        /** 模型 id，全局唯一，也是 token 统计里的分类名。 */
        public String id = "";
        /** 角色：judge（判断是否接话）/ chat（真正回复，可多个）/ admin（管理员特权请求）。 */
        public String role = "chat";
        public boolean enabled = true;
        /** 配置目录（相对 models_dir，留空就用 id）。 */
        public String dir = "";
        /** 仅 judge 使用：判断为「要回复」后路由给哪些 chat 模型；留空 = 所有启用的 chat 模型。 */
        public List<String> routes_to = new ArrayList<>();
        /** 仅 chat 使用：是否参与 AI 之间的自动对话。 */
        public boolean reply_to_ai = true;
    }

    // ---------------- 双模型（2.0.x，已弃用） ----------------

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
