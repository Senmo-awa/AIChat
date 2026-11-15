package com.example.aichat.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 一个模型的完整运行参数：编排信息（id / 角色 / 路由）来自 {@code system.json}，
 * 连接参数来自模型目录里的 {@code model.json}，留空的字段已经在这里合并成 config.json 的值。
 * <p>
 * 合并之后 {@link com.example.aichat.config.ConfigManager#profileFor(ModelSpec)} 只做一件事：
 * 拼成协议层认识的 {@code ApiProfile}。
 */
public record ModelSpec(
        String id,
        Role role,
        boolean enabled,
        Path dir,
        String apiUrl,
        String apiKey,
        String model,
        String apiType,
        String systemPromptFile,
        String personaPromptFile,
        String replyPrefix,
        String selfName,
        int maxOutputTokens,
        int thinkingBudgetTokens,
        boolean enableThinking,
        boolean sendThinkingField,
        String reasoningEffort,
        boolean useJsonMode,
        boolean enableTools,
        boolean memoryEnabled,
        List<String> routesTo,
        boolean replyToAi
) {
    /** 模型角色。 */
    public enum Role {
        /** 只判断「要不要接话」，输出一行 JSON。 */
        JUDGE,
        /** 真正写聊天内容，可以配多个。 */
        CHAT,
        /** 接管管理员特权请求。 */
        ADMIN;

        public static Role parse(String raw) {
            if (raw == null) {
                return CHAT;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "judge", "判断", "判断模型" -> JUDGE;
                case "admin", "管理", "管理模型" -> ADMIN;
                default -> CHAT;
            };
        }

        public String label() {
            return switch (this) {
                case JUDGE -> "judge";
                case ADMIN -> "admin";
                case CHAT -> "chat";
            };
        }
    }

    /** 在 AI 互相对话里显示的名字。 */
    public String displayName() {
        return selfName == null || selfName.isBlank() ? id : selfName;
    }
}
