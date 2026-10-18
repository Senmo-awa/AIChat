package com.example.aichat.usage;

/**
 * 单次 AI 对话（可能包含多轮 tool call）累计的 token 用量。
 */
public record TokenUsage(long prompt, long completion, long total) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                prompt + other.prompt,
                completion + other.completion,
                total + other.total
        );
    }
}
