package com.example.aichat.ai;

import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.google.gson.JsonObject;

/**
 * 兜底话术：工具轮次用尽、或模型没给出可解析的 JSON 时，代替沉默播报一句话。
 * <p>
 * 为什么需要它：模型在「工具声明被抽掉」或「轮次用尽」后会退化成写私有工具标记
 * （实测 DeepSeek 会输出 {@code <｜｜DSML｜｜ calls>}），模组解析不了就只能丢弃，
 * 玩家端表现为「AI 明明在线却一个字都不回」——比回一句「没理出结果」体验更差。
 * <p>
 * 兜底话术以合法 JSON 回复的形式返回，后续流程与正常回复完全一致（也进历史）。
 */
public final class Fallbacks {
    /** 模型输出里出现这些标记，说明它想调用工具却写成了正文。 */
    private static final String[] TOOL_MARKUP_HINTS = {
            "DSML",
            "<|tool_call",
            "<function_calls>",
            "antml:invoke",
            "｜｜"
    };

    private Fallbacks() {
    }

    /** 配置了兜底话术时返回它的 JSON 回复，否则返回 null（保持静默）。 */
    public static String replyJsonOrNull() {
        ModConfig config = ConfigManager.get();
        if (config == null || config.fallback_reply == null || config.fallback_reply.isBlank()) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("action", "reply");
        json.addProperty("content", config.fallback_reply.trim());
        return json.toString();
    }

    /** 内容看起来是模型私有的工具调用标记（而不是给人看的文字）。 */
    public static boolean looksLikeToolMarkup(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (String hint : TOOL_MARKUP_HINTS) {
            if (content.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
