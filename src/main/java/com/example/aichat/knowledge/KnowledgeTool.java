package com.example.aichat.knowledge;

import com.example.aichat.tool.Schemas;
import com.example.aichat.tool.Tool;
import com.example.aichat.tool.ToolRun;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 知识库读取工具（read_knowledge_file）。
 * 文件清单写进描述里：不写真实文件名模型就会瞎猜。
 */
public final class KnowledgeTool implements Tool {
    @Override
    public String name() {
        return "read_knowledge_file";
    }

    @Override
    public String description() {
        List<String> files = KnowledgeReader.listFiles();
        String list = files.isEmpty() ? "（知识库为空）" : String.join("、", files);
        return "当需要查阅知识库文件时调用。file_name 只能取以下之一：" + list;
    }

    @Override
    public JsonObject parameters() {
        List<String> files = KnowledgeReader.listFiles();
        String list = files.isEmpty() ? "（知识库为空）" : String.join("、", files);
        JsonObject properties = new JsonObject();
        properties.add("file_name", Schemas.stringProperty("知识库文件名，可选值：" + list));
        return Schemas.object(properties, "file_name");
    }

    @Override
    public String invoke(JsonObject arguments, ToolRun run) throws Exception {
        JsonElement element = arguments.get("file_name");
        String fileName = element == null || element.isJsonNull() ? "" : element.getAsString().trim();
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("缺少 file_name");
        }
        return KnowledgeReader.read(fileName);
    }
}
