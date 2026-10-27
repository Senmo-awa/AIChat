package com.example.aichat.memory;

import com.example.aichat.tool.Schemas;
import com.example.aichat.tool.Tool;
import com.example.aichat.tool.ToolRun;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * 记忆写入工具（memory_write）：新建、追加或覆写记忆文件。
 * <p>
 * 超过 memory_max_file_kb 的写入会被拒绝，并回一段「先读全文 → 压缩 → overwrite」的指引，
 * 由模型自己完成压缩，不在模组侧静默截断。
 */
public final class MemoryWriteTool implements Tool {
    @Override
    public String name() {
        return "memory_write";
    }

    @Override
    public String description() {
        return "写入你的记忆文件，跨对话保存。mode=append 追加到末尾（默认），mode=overwrite 整篇覆写。"
                + "文件过大会被拒绝并提示压缩，此时先 memory_read 读出全文，压缩成要点再 overwrite。";
    }

    @Override
    public JsonObject parameters() {
        JsonObject properties = new JsonObject();
        properties.add("file_name",
                Schemas.stringProperty("记忆文件名，如 玩家约定.md 或 reminders.md；不带扩展名会补 .md"));
        properties.add("content", Schemas.stringProperty("要写入的正文，简洁的要点即可"));
        properties.add("mode", Schemas.enumProperty("写入方式：append 追加（默认）/ overwrite 覆写",
                "append", "overwrite"));
        return Schemas.object(properties, "file_name", "content");
    }

    @Override
    public String invoke(JsonObject arguments, ToolRun run) throws Exception {
        String fileName = text(arguments, "file_name");
        String content = text(arguments, "content");
        boolean append = !"overwrite".equals(text(arguments, "mode").toLowerCase(Locale.ROOT));
        return MemoryStore.write(fileName, content, append);
    }

    @Override
    public int maxResultChars() {
        return 1200;
    }

    private static String text(JsonObject arguments, String field) {
        JsonElement element = arguments.get(field);
        return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
    }
}
