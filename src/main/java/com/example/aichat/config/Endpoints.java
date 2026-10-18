package com.example.aichat.config;

import java.util.Locale;

/**
 * 端点补全与协议识别。允许用户只填 base URL。
 */
public final class Endpoints {
    private Endpoints() {
    }

    public static String resolveType(String type, String url) {
        String value = type == null ? "auto" : type.trim().toLowerCase(Locale.ROOT);
        if ("anthropic".equals(value) || "claude".equals(value)) {
            return "anthropic";
        }
        if ("deepseek".equals(value)) {
            return "deepseek";
        }
        if ("openai".equals(value)) {
            return "openai";
        }
        String normalized = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (normalized.contains("anthropic.com") || normalized.endsWith("/messages")) {
            return "anthropic";
        }
        if (normalized.contains("api.deepseek.com")) {
            return "deepseek";
        }
        return "openai";
    }

    public static String chatCompletions(String url) {
        String normalized = stripTrailingSlash(url == null ? "" : url.trim());
        if (normalized.isEmpty()) {
            normalized = "https://api.deepseek.com";
        }
        if (!normalized.endsWith("/chat/completions")) {
            normalized = normalized + "/chat/completions";
        }
        return normalized;
    }

    public static String messages(String url) {
        String normalized = stripTrailingSlash(url == null ? "" : url.trim());
        if (normalized.isEmpty()) {
            normalized = "https://api.anthropic.com";
        }
        if (normalized.endsWith("/messages")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/messages";
        }
        return normalized + "/v1/messages";
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
