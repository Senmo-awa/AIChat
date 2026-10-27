package com.example.aichat.memory;

import com.example.aichat.tool.Schemas;
import com.example.aichat.tool.Tool;
import com.example.aichat.tool.ToolRun;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 记忆读取工具（memory_read）：省略 file_name 时返回文件清单，否则读取全文。
 */
public final class MemoryReadTool implements Tool {
    @Override
    public String name() {
        return "memory_read";
    }

    @Override
    public String description() {
        return "读取你的记忆文件（跨对话保存的笔记）。不填 file_name 时返回所有记忆文件的清单和大小；"
                + "填了则返回该文件全文。需要回忆玩家之前托付的事情时先调用它。";
    }

    @Override
    public JsonObject parameters() {
        JsonObject properties = new JsonObject();
        properties.add("file_name",
                Schemas.stringProperty("记忆文件名（.md / .txt）；留空则只返回文件清单"));
        return Schemas.object(properties);
    }

    @Override
    public String invoke(JsonObject arguments, ToolRun run) throws Exception {
        JsonElement element = arguments.get("file_name");
        String fileName = element == null || element.isJsonNull() ? "" : element.getAsString().trim();
        return fileName.isEmpty() ? MemoryStore.index() : MemoryStore.read(fileName);
    }

    /**
     * 记忆文件本来就受 memory_max_file_kb 约束，这里按上限放宽截断阈值，
     * 保证模型压缩时能读到完整内容（UTF-8 字节数不小于字符数，所以按字节上限取字符数一定够用）。
     */
    @Override
    public int maxResultChars() {
        return Math.max(DEFAULT_MAX_RESULT_CHARS, MemoryStore.limitBytes());
    }
}
