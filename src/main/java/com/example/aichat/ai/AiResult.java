package com.example.aichat.ai;

import com.example.aichat.usage.TokenUsage;

/**
 * usage 是本次对话（含工具调用的追加请求）累计的 token 用量。
 */
public record AiResult(boolean reply, String content, TokenUsage usage) {
}
