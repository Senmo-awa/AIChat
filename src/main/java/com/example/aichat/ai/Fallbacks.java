package com.example.aichat.ai;

import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    /**
     * 回灌工具结果后再钉一次格式契约。
     * 实测 deepseek-v4.1-flash 连着调 3 轮工具之后会彻底忘掉 system 里的 JSON 契约，
     * 第 4 轮直接回一句「收到喵～」，模组解析失败 → 玩家端静默失联。
     */
    public static final String TOOL_FOLLOWUP_REMINDER =
            "【系统提醒】以上是工具返回的结果。信息够了就按 system 规则只输出最终 JSON"
                    + "（{\"action\":\"reply\",\"content\":\"...\"} 或 {\"action\":\"ignore\"}），"
                    + "不要再写自然语言段落；确实还缺数据就继续调用工具。";

    /**
     * 检测到模型脱轨（用自然语言而不是 JSON 回话）后，补一次「改写」请求时追加的要求。
     * 工具声明保留着，所以必须明说「不要调用工具」，否则它可能又去调工具。
     */
    public static final String JSON_REPAIR_REMINDER =
            "【系统】上一条回复没有按要求的 JSON 格式输出。请把同样的意思改写成 JSON："
                    + "只输出 {\"action\":\"reply\",\"content\":\"...\"}（或 {\"action\":\"ignore\"}），"
                    + "不要调用工具，也不要输出任何额外文字。";

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

    /**
     * 模型把最终回答写成了自然语言（不是 JSON）时，把原文包成合法回复。
     * 这是长工具链之后最常见的脱轨方式，而原文本来就是要发给玩家的话，救回来远好过丢弃。
     * <p>
     * 返回 null 表示不该这样兜底：内容为空、是私有工具标记、或者整段其实是一份（可解析的）
     * JSON 对象——后者交给上层按正常解析流程处理。
     */
    public static String replyJsonFromProse(String prose) {
        if (prose == null || prose.isBlank() || looksLikeToolMarkup(prose) || isJsonObject(prose)) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("action", "reply");
        json.addProperty("content", prose.trim());
        return json.toString();
    }

    /** 内容（剥掉代码块围栏和前后杂字后）是不是一份可解析的 JSON 对象。 */
    public static boolean isJsonObject(String content) {
        String jsonText = AiClient.extractJson(content);
        if (jsonText == null) {
            return false;
        }
        try {
            return JsonParser.parseString(jsonText).isJsonObject();
        } catch (RuntimeException e) {
            return false;
        }
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
