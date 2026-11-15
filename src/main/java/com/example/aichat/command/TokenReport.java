package com.example.aichat.command;

import com.example.aichat.usage.UsageStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /aichat token} 系列的展示层：把账本数据排成一张**带颜色、带对齐**的小表格。
 * <p>
 * 以前是一整块灰白文字（{@code 1234 + 567 = 1801 tokens，12 次}），五类参数挤在一起，
 * 一眼分不清哪个数字是输入、哪个是合计。这里做了两件事：
 * <ol>
 *   <li><b>每类参数固定一个颜色</b>：日期/玩家名冷蓝、输入暖黄、输出珊瑚红、合计绿、次数紫。
 *       表头是一条颜色图例（{@code 日期 · 输入 · 输出 · 合计 · 次数}），滚屏之后也认得出哪个数字是什么。</li>
 *   <li><b>数值列按固定宽度右对齐</b>，每行以一条暗灰竖线 {@code | } 起头当视觉锚点，
 *       图例 / 分隔线 / 数据行共用同一套列宽。</li>
 * </ol>
 * 两个刻意的取舍：
 * <ul>
 *   <li>表格里**不放中文标签**（日期是 ASCII、玩家名一般是 ASCII）。中文在聊天框里的宽度和半角字符
 *       不是整数倍关系，一旦把中文塞进「名字列」，后面所有数字列都会整体偏移；
 *       所以合计这种必须带中文的行做成表格下方的落脚行（{@code - 合计 输入 … · 输出 … -}）。</li>
 *   <li>不做像素级对齐：MC 聊天框字体不是等宽字体（空格 4px、数字 6px），只能做到「字符数对齐」，
 *       位数不同的行之间会有 1~2px 抖动——竖线只放在行首，就是为了不让这点抖动看起来像排版错误。</li>
 * </ul>
 */
public final class TokenReport {
    // ---- 配色（GitHub 深色系，聊天框半透明深底上对比度足够）----
    /** 标题 / 蓝。 */
    private static final int C_TITLE = 0x58A6FF;
    /** 日期或玩家名 / 浅蓝。 */
    private static final int C_NAME = 0x79C0FF;
    /** 输入（prompt）/ 暖黄。 */
    private static final int C_PROMPT = 0xF2CC60;
    /** 输出（completion）/ 珊瑚红。 */
    private static final int C_COMPLETION = 0xFF7B72;
    /** 合计（total）/ 绿。 */
    private static final int C_TOTAL = 0x7EE787;
    /** 次数（requests）/ 紫。 */
    private static final int C_REQUESTS = 0xD2A8FF;
    /** 说明文字 / 灰。 */
    private static final int C_LABEL = 0x8B949E;
    /** 行首竖线与分隔线 / 暗灰。 */
    private static final int C_RULE = 0x3D444D;

    // ---- 列宽（半角字符数）----
    private static final int W_DATE = 10;
    private static final int W_PROMPT = 6;
    private static final int W_COMPLETION = 6;
    private static final int W_TOTAL = 8;
    private static final int W_REQUESTS = 5;

    private TokenReport() {
    }

    /**
     * 公屏账本的按日表格。
     *
     * @param days 「最近 N 天」的按日汇总，按时间正序（没有记录的日期也在里面）
     */
    public static List<Component> daily(List<UsageStore.DailyUsage> days) {
        List<Component> lines = new ArrayList<>();
        int span = days == null ? 7 : days.size();
        lines.add(title("Token 用量", "最近 " + span + " 天"));
        if (days == null || days.isEmpty()) {
            lines.add(footnote("这段时间还没有记录"));
            return lines;
        }

        lines.add(legend("日期"));
        lines.add(rule(W_DATE));
        long prompt = 0;
        long completion = 0;
        long total = 0;
        int requests = 0;
        for (UsageStore.DailyUsage day : days) {
            lines.add(row(day.date(), day.prompt(), day.completion(), day.total(), day.requests(), W_DATE));
            prompt += day.prompt();
            completion += day.completion();
            total += day.total();
            requests += day.requests();
        }
        lines.add(rule(W_DATE));
        lines.add(footnote(totals(prompt, completion, total, requests)));
        return lines;
    }

    /** 私聊账本按玩家明细（总量从大到小）。 */
    public static List<Component> privateByPlayer(List<UsageStore.PlayerUsage> players) {
        List<Component> lines = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (players != null) {
            for (UsageStore.PlayerUsage player : players) {
                names.add(player.player());
            }
        }
        int nameWidth = nameWidth(names);
        lines.add(title("私聊 Token", "最近 7 天"));
        if (names.isEmpty()) {
            lines.add(footnote("最近 7 天还没有私聊记录"));
            return lines;
        }

        lines.add(legend("玩家"));
        lines.add(rule(nameWidth));
        long prompt = 0;
        long completion = 0;
        long total = 0;
        int requests = 0;
        for (UsageStore.PlayerUsage player : players) {
            lines.add(row(player.player(), player.prompt(), player.completion(), player.total(),
                    player.requests(), nameWidth));
            prompt += player.prompt();
            completion += player.completion();
            total += player.total();
            requests += player.requests();
        }
        lines.add(rule(nameWidth));
        lines.add(footnote(totals(prompt, completion, total, requests)));
        return lines;
    }

    /** 多模型模式下按模型分账。 */
    public static List<Component> byModel(List<UsageStore.ModelUsage> models) {
        List<Component> lines = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (models != null) {
            for (UsageStore.ModelUsage model : models) {
                names.add(model.model());
            }
        }
        int nameWidth = nameWidth(names);
        lines.add(title("按模型分账", "最近 7 天"));
        if (names.isEmpty()) {
            lines.add(footnote("最近 7 天还没有按模型的记录"));
            return lines;
        }

        lines.add(legend("模型"));
        lines.add(rule(nameWidth));
        long prompt = 0;
        long completion = 0;
        long total = 0;
        int requests = 0;
        for (UsageStore.ModelUsage model : models) {
            lines.add(row(model.model(), model.prompt(), model.completion(), model.total(),
                    model.requests(), nameWidth));
            prompt += model.prompt();
            completion += model.completion();
            total += model.total();
            requests += model.requests();
        }
        lines.add(rule(nameWidth));
        lines.add(footnote(totals(prompt, completion, total, requests)));
        return lines;
    }

    /** 公屏表格末尾那行「私聊另一本账」，单独一行免得和公屏数字混在一起。 */
    public static Component privateSummary(long total, int requests, int players) {
        Line line = new Line(text("- ", C_RULE))
                .append(dim("私聊最近 7 天："))
                .append(strong(Long.toString(total), C_TOTAL))
                .append(dim(" tokens · "))
                .append(text(Integer.toString(requests), C_REQUESTS))
                .append(dim(" 次"));
        if (players <= 0) {
            line.append(dim("（暂无）"));
        } else {
            line.append(dim(" · "))
                    .append(text(Integer.toString(players), C_NAME))
                    .append(dim(" 位玩家可查 /aichat token pm"));
        }
        line.append(text(" -", C_RULE));
        return line.build();
    }

    /** {@code /aichat status} 的「今日用量」那一行，配色与表格保持一致。 */
    public static Component statusLine(long today, long budget, long remain, int requests) {
        String budgetText = budget <= 0 ? "不限" : Long.toString(budget);
        String remainText = budget <= 0 ? "不限" : Long.toString(remain);
        return Component.empty()
                .append(dim("今日 token："))
                .append(strong(Long.toString(today), C_TOTAL))
                .append(dim(" / 预算 "))
                .append(text(budgetText, C_PROMPT))
                .append(dim("（剩 "))
                .append(text(remainText, C_PROMPT))
                .append(dim("）· "))
                .append(text(Integer.toString(requests), C_REQUESTS))
                .append(dim(" 次请求"));
    }

    // ---- 排版零件 ----

    private static Component title(String subject, String scope) {
        return Component.empty()
                .append(text("- ", C_RULE))
                .append(strong(subject, C_TITLE))
                .append(dim(" · " + scope))
                .append(text(" -", C_RULE));
    }

    /**
     * 表头做成「图例」而不是压在列上的列标题：每个词用对应列的颜色，顺序与下面的数字一致。
     * 中文标题压不到数字列上（宽度不是整数倍），声明「顺序 + 颜色」反而不会看出对不齐。
     */
    private static Component legend(String nameLabel) {
        return rowStart()
                .append(text(nameLabel, C_LABEL))
                .append(dim(" · "))
                .append(text("输入", C_PROMPT))
                .append(dim(" · "))
                .append(text("输出", C_COMPLETION))
                .append(dim(" · "))
                .append(text("合计", C_TOTAL))
                .append(dim(" · "))
                .append(text("次数", C_REQUESTS))
                .build();
    }

    /** 表格里的分隔线：宽度与数据行一致，右端不会多出一截。 */
    private static Component rule(int nameWidth) {
        int width = nameWidth + 1 + W_PROMPT + 2 + W_COMPLETION + 2 + W_TOTAL + 2 + W_REQUESTS;
        return rowStart().append(text("-".repeat(width), C_RULE)).build();
    }

    private static Component row(String name, long prompt, long completion, long total, int requests,
                                 int nameWidth) {
        return rowStart()
                .append(text(padRight(name, nameWidth), C_NAME))
                .append(plain(" "))
                .append(cell(prompt, W_PROMPT, C_PROMPT))
                .append(plain("  "))
                .append(cell(completion, W_COMPLETION, C_COMPLETION))
                .append(plain("  "))
                .append(cell(total, W_TOTAL, C_TOTAL))
                .append(plain("  "))
                .append(cell(requests, W_REQUESTS, C_REQUESTS))
                .build();
    }

    /** 表格下方的合计行：纯文字叙述，不塞进表格（中文标签会把数字列顶歪）。 */
    private static Component totals(long prompt, long completion, long total, int requests) {
        return Component.empty()
                .append(dim("合计 "))
                .append(dim("输入 "))
                .append(strong(Long.toString(prompt), C_PROMPT))
                .append(dim(" · 输出 "))
                .append(strong(Long.toString(completion), C_COMPLETION))
                .append(dim(" · 总计 "))
                .append(strong(Long.toString(total), C_TOTAL))
                .append(dim(" tokens · "))
                .append(text(Integer.toString(requests), C_REQUESTS))
                .append(dim(" 次请求"));
    }

    /** 表格下方的一行说明（两端用 {@code - } 包起来，和标题同款，明确「这是表格外面」）。 */
    private static Component footnote(String content) {
        return footnote(Component.literal(content));
    }

    private static Component footnote(Component content) {
        return Component.empty()
                .append(text("- ", C_RULE))
                .append(content)
                .append(text(" -", C_RULE));
    }

    /** 行首的「| 」竖线：所有行完全一致，作为左对齐的视觉锚点。 */
    private static Line rowStart() {
        return new Line(text("| ", C_RULE));
    }

    private static Component cell(long value, int width, int color) {
        return text(String.format("%" + width + "d", value), color);
    }

    private static MutableComponent text(String text, int color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
    }

    private static MutableComponent plain(String text) {
        return Component.literal(text);
    }

    private static MutableComponent dim(String text) {
        return text(text, C_LABEL);
    }

    private static MutableComponent strong(String text, int color) {
        return text(text, color).withStyle(ChatFormatting.BOLD);
    }

    /** 玩家名 / 模型 id 的列宽：按最长的一个自适应，太长会折行所以封顶 16。 */
    private static int nameWidth(List<String> names) {
        int width = 6;
        for (String name : names) {
            width = Math.max(width, units(name));
        }
        return Math.min(width, 16);
    }

    /** 估算显示宽度：中日韩全角算 2，其余算 1（聊天框不是等宽字体，只能取近似值）。 */
    private static int units(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return width;
    }

    private static String padRight(String text, int width) {
        int pad = width - units(text);
        return pad <= 0 ? text : text + " ".repeat(pad);
    }

    /** 拼装用的小工具：一次 {@code append} 一串片段，最后 {@code build()} 成一行。 */
    private static final class Line {
        private final MutableComponent component;

        private Line(MutableComponent component) {
            this.component = component;
        }

        private Line append(Component part) {
            component.append(part);
            return this;
        }

        private Component build() {
            return component;
        }
    }
}
