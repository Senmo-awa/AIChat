package com.example.aichat.text;

import java.util.regex.Pattern;

/**
 * 广播前的密钥脱敏。
 * <p>
 * 挡的是「AI 自己把秘密说出来」：{@code server_files} 能读服务器文件，
 * 提示词也可能被玩家套出上游配置。哪怕默认拒绝清单已经挡住了 {@code server.properties}，
 * 也别指望只靠路径白名单——出口再抹一遍，代价几乎为零。
 * <p>
 * 只处理明显像密钥的形状（{@code sk-xxx}、{@code Bearer xxx}、{@code password=xxx}），
 * 不做通用高熵字符串检测：那会把玩家正常的讨论（哈希、Base64 数据）也一起吃掉。
 */
public final class SecretRedactor {
    private static final String MASK = "***";

    /** DeepSeek / OpenAI 风格的 API Key。 */
    private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    /** Authorization: Bearer xxx */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");

    /** key=value / key: value 形式的凭据，value 至少 4 个字符才抹（避免把 "password: 空" 也抹了）。 */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|secret|password|passwd)"
                    + "(\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;}{]{4,})");

    private SecretRedactor() {
    }

    /** 抹掉文本里的疑似密钥；null / 空串原样返回。 */
    public static String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = API_KEY.matcher(text).replaceAll("sk-" + MASK);
        result = BEARER.matcher(result).replaceAll("$1" + MASK);
        result = KEY_VALUE.matcher(result).replaceAll("$1$2" + MASK);
        return result;
    }
}
