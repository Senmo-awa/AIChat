package com.example.aichat.text;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * Windows 中文环境下控制台是 GBK（cp936），MCDR 之类的包装器还会用 GBK 写自己的 stdout。
 * 模型回复里的颜文字常含 GBK 表示不了的字符（｡ ・ ∀ ﾟ ♪ ๑ ❤ 等），MCDR 写日志时会抛
 * UnicodeEncodeError，甚至让主循环 CRITICAL。
 *
 * 这个类只处理「写给控制台/日志」的文本：游戏内玩家收到的消息走网络 UTF-8，保持原样不受影响。
 */
public final class ConsoleSafe {
    private static final Charset CHARSET = resolveCharset();

    private ConsoleSafe() {
    }

    private static Charset resolveCharset() {
        // sun.stdout.encoding 在能识别控制台编码时才存在（UTF-8 控制台下就是 UTF-8，此时不过滤）；
        // stdout 被重定向（MCDR / 面板）时它为空，退回操作系统的本地编码（中文 Windows 是 GBK）。
        String name = System.getProperty("sun.stdout.encoding");
        if (name == null || name.isBlank()) {
            name = System.getProperty("native.encoding");
        }
        try {
            return name == null || name.isBlank() ? Charset.defaultCharset() : Charset.forName(name);
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }

    /**
     * 控制台广播用：直接丢掉目标编码表示不了的字符。
     */
    public static String drop(String text) {
        return transform(text, false);
    }

    /**
     * 日志用：把表示不了的字符转成 \\uXXXX，信息不丢，也不会让下游编码器崩掉。
     */
    public static String escape(String text) {
        return transform(text, true);
    }

    private static String transform(String text, boolean escape) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        CharsetEncoder encoder = CHARSET.newEncoder();
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            String chunk = text.substring(i, i + width);
            if (encoder.canEncode(chunk)) {
                out.append(chunk);
            } else if (escape) {
                if (codePoint > 0xFFFF) {
                    out.append("\\u{").append(Integer.toHexString(codePoint).toUpperCase()).append('}');
                } else {
                    out.append("\\u").append(String.format("%04X", codePoint));
                }
            }
            i += width;
        }
        return out.toString();
    }
}
