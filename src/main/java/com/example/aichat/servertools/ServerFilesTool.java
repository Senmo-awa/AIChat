package com.example.aichat.servertools;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.example.aichat.text.ConsoleSafe;
import com.example.aichat.tool.Schemas;
import com.example.aichat.tool.Tool;
import com.example.aichat.tool.ToolRun;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * 服务器管理工具（server_files）：一个工具覆盖查看 / 管理 / 新增 / 删除。
 * <p>
 * 只在「特权请求」里注册（消息带前缀 + 玩家有权限 + 总开关打开），并且在 {@link #invoke} 里
 * 再查一次 {@link ToolRun#serverToolsAllowed()}：即使哪天注册逻辑写漏，调用也会被拒。
 * 具体文件操作与安全检查在 {@link ServerFiles}。
 */
public final class ServerFilesTool implements Tool {
    public static final String NAME = "server_files";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "服务器根目录文件管理（仅限有权限的管理员请求）。"
                + "op=list 列目录；read 读文本文件；write 新建/覆写/追加；mkdir 建目录；delete 删除（默认移入 .aichat-trash 回收目录）；"
                + "move 移动或改名；info 看类型/大小/修改时间；search 按文件名关键词查找（支持 * 通配）。"
                + "path 一律写相对服务器根目录的路径（如 mods、logs/latest.log）。"
                + "先 list / info 看清目标再动手；删除前必须先确认目标；大文件读取会被截断，二进制文件不返回内容。";
    }

    @Override
    public JsonObject parameters() {
        JsonObject properties = new JsonObject();
        properties.add("op", Schemas.enumProperty(
                "要执行的操作",
                "list", "read", "write", "mkdir", "delete", "move", "info", "search"));
        properties.add("path", Schemas.stringProperty(
                "相对服务器根目录的路径，如 mods、config/xxx.json、logs/latest.log；list/info/search 省略表示根目录"));
        properties.add("content", Schemas.stringProperty("op=write 时要写入的正文"));
        properties.add("mode", Schemas.enumProperty(
                "op=write 的写入方式：append 追加（默认，更安全）/ overwrite 覆写", "append", "overwrite"));
        properties.add("dest", Schemas.stringProperty("op=move 的目标路径"));
        properties.add("pattern", Schemas.stringProperty("op=search 的文件名关键词，支持 * 通配，例如 *.jar"));
        properties.add("recursive", Schemas.booleanProperty(
                "op=delete 时是否连非空目录一起删（默认 false，遇到非空目录会拒绝）", false));
        properties.add("create_parents", Schemas.booleanProperty(
                "op=write 时父目录不存在是否自动创建（默认 true）", true));
        return Schemas.object(properties, "op");
    }

    @Override
    public String invoke(JsonObject arguments, ToolRun run) throws Exception {
        if (run == null || !run.serverToolsAllowed()) {
            throw new IllegalStateException("当前对话没有服务器管理权限："
                    + "只有管理员用前缀发起的请求才能调用这个工具。");
        }
        String op = text(arguments, "op").toLowerCase(Locale.ROOT);
        String path = text(arguments, "path");
        AiChatMod.LOGGER.info("[server-tools] 管理员 {} 调用 op={} path={}",
                run.actorName().isBlank() ? "?" : run.actorName(),
                op.isEmpty() ? "?" : op,
                ConsoleSafe.escape(path.isEmpty() ? "." : path));

        return switch (op) {
            case "list" -> ServerFiles.list(path);
            case "read" -> ServerFiles.read(path);
            case "write" -> ServerFiles.write(
                    path,
                    text(arguments, "content"),
                    !"overwrite".equals(text(arguments, "mode").toLowerCase(Locale.ROOT)),
                    bool(arguments, "create_parents", true));
            case "mkdir" -> ServerFiles.mkdir(path);
            case "delete" -> ServerFiles.delete(path, bool(arguments, "recursive", false));
            case "move" -> ServerFiles.move(path, text(arguments, "dest"));
            case "info" -> ServerFiles.info(path);
            case "search" -> ServerFiles.search(path, text(arguments, "pattern"));
            default -> throw new IllegalArgumentException("未知 op：" + op
                    + "（可用 list / read / write / mkdir / delete / move / info / search）");
        };
    }

    /**
     * 读取上限就是单次读取的字节上限，再留一点头部说明的余量；
     * 不然 Tools 层会把它当普通工具截断成 6000 字符。
     */
    @Override
    public int maxResultChars() {
        ModConfig config = ConfigManager.get();
        int kb = config == null || config.server_tools_read_max_kb < 1 ? 48 : config.server_tools_read_max_kb;
        return Math.max(DEFAULT_MAX_RESULT_CHARS, kb * 1024 + 800);
    }

    private static String text(JsonObject arguments, String field) {
        JsonElement element = arguments.get(field);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        return element.getAsString().trim();
    }

    private static boolean bool(JsonObject arguments, String field, boolean fallback) {
        JsonElement element = arguments.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            if (element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isBoolean()) {
                    return element.getAsBoolean();
                }
                String raw = element.getAsString().trim().toLowerCase(Locale.ROOT);
                return "true".equals(raw) || "1".equals(raw) || "yes".equals(raw);
            }
        } catch (RuntimeException ignored) {
            // 模型给了奇奇怪怪的值，用默认值兜底
        }
        return fallback;
    }
}
