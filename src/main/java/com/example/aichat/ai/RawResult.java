package com.example.aichat.ai;

import com.example.aichat.usage.TokenUsage;

/**
 * 协议层的原始返回：模型文本 + 本次对话（含工具轮次）的累计用量。
 * 业务解析（判断是否回复 / 提取内容）由 AiClient 负责。
 */
public record RawResult(String text, TokenUsage usage) {
}
