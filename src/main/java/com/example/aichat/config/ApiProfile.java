package com.example.aichat.config;

/**
 * 一次请求所需的完整 API 配置。单模型模式和双模型模式都通过它驱动协议层，
 * 协议层不再直接读全局配置。
 * <p>
 * toolsEnabled 与 memoryEnabled 分开传：前者是老的 enable_tools（知识库 + 搜索），
 * 后者是记忆功能。任一为 true 时都要发送工具定义，但暴露哪些工具由 Tool 注册表决定。
 */
public record ApiProfile(
        String apiUrl,
        String apiKey,
        String model,
        String apiType,
        String systemPrompt,
        boolean toolsEnabled,
        boolean memoryEnabled,
        boolean jsonMode,
        boolean thinkingEnabled,
        boolean sendThinkingField,
        String reasoningEffort,
        int maxOutputTokens,
        int thinkingBudgetTokens,
        boolean systemInUserMessage
) {
}
