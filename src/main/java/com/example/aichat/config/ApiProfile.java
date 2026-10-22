package com.example.aichat.config;

/**
 * 一次请求所需的完整 API 配置。单模型模式和双模型模式都通过它驱动协议层，
 * 协议层不再直接读全局配置。
 */
public record ApiProfile(
        String apiUrl,
        String apiKey,
        String model,
        String apiType,
        String systemPrompt,
        boolean toolsEnabled,
        boolean jsonMode,
        boolean thinkingEnabled,
        boolean sendThinkingField,
        String reasoningEffort,
        int maxOutputTokens,
        int thinkingBudgetTokens,
        boolean systemInUserMessage
) {
}
