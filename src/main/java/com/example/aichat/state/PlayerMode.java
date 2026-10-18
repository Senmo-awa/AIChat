package com.example.aichat.state;

import java.util.Locale;

/**
 * 玩家自己的 AI 聊天状态。
 * ON：正常，消息发给 AI，也能看到 AI 消息。
 * OFF：双向静默，消息不给 AI，也看不到 AI 消息。
 * ONLY_READ：消息不给 AI，但能看到 AI 消息。
 */
public enum PlayerMode {
    ON,
    OFF,
    ONLY_READ;

    public static PlayerMode parse(String raw) {
        if (raw == null) {
            return ON;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(value)) {
            return OFF;
        }
        if ("onlyread".equals(value) || "only_read".equals(value)) {
            return ONLY_READ;
        }
        return ON;
    }

    public String id() {
        return this == ONLY_READ ? "onlyread" : name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return switch (this) {
            case ON -> "开启";
            case OFF -> "关闭";
            case ONLY_READ -> "只读";
        };
    }
}
