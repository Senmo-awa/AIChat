package com.example.aichat.ai;

/**
 * 一条历史对话：role 为 user（玩家事件）或 assistant（AI 回复）。
 */
public record ConversationTurn(String role, String content) {
}
