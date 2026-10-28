package com.example.aichat.servertools;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 服务器根目录（Fabric 的 game dir）上的文件操作。
 * <p>
 * 安全约定：
 * <ul>
 *   <li>所有路径都相对服务器根目录解析，解析后必须仍然落在根目录内（含符号链接解析后的真实路径）；</li>
 *   <li>{@code server_tools_deny_paths} 里的路径（默认挡住本模组自己的配置，里面有 API Key）读写都拒绝；</li>
 *   <li>读取有字节上限、跳过二进制文件；写入有字节上限；</li>
 *   <li>删除默认移入 {@code .aichat-trash/<时间戳>/}，不是直接抹掉。</li>
 * </ul>
 * 所有方法都是同步的：AI 工具调用本来就跑在单线程 worker 上，加锁是为了防止多个请求交叉改同一个文件。
 */
public final class ServerFiles {
    private static final String TRASH_DIR = ".aichat-trash";
    private static final int BINARY_PROBE = 8192;
    private static final int SEARCH_LIMIT = 100;
    private static final int SEARCH_MAX_VISITED = 20_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Object LOCK = new Object();

    private ServerFiles() {
    }

    /** 服务器根目录（模组运行目录）。 */
    public static Path root() {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    }

    // ---------------- 各个操作 ----------------

    public static String list(String rawPath) throws IOException {
        synchronized (LOCK) {
            Path dir = resolve(rawPath);
            if (!Files.isDirectory(dir)) {
                throw new IOException("不是目录：" + display(dir) + "（看单个文件请用 op=info 或 op=read）");
            }
            List<Path> children = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.forEach(children::add);
            }
            children.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));

            int limit = listLimit();
            StringBuilder text = new StringBuilder()
                    .append("目录 ").append(display(dir))
                    .append("（共 ").append(children.size()).append(" 项）\n");
            int shown = 0;
            for (Path child : children) {
                if (isDenied(child)) {
                    continue;
                }
                if (shown >= limit) {
                    text.append("…（只显示前 ").append(limit).append(" 项，用 op=search 缩小范围）\n");
                    break;
                }
                boolean directory = Files.isDirectory(child);
                text.append(directory ? "d " : "f ").append(child.getFileName());
                if (directory) {
                    text.append('/');
                } else {
                    text.append("  ").append(humanSize(sizeOf(child)));
                }
                text.append("  ").append(timeOf(child)).append('\n');
                shown++;
            }
            if (children.isEmpty()) {
                text.append("（空目录）\n");
            }
            return text.toString().stripTrailing();
        }
    }

    public static String read(String rawPath) throws IOException {
        synchronized (LOCK) {
            Path file = resolve(rawPath);
            if (Files.isDirectory(file)) {
                throw new IOException("这是目录，请用 op=list：" + display(file));
            }
            if (!Files.isRegularFile(file)) {
                throw new IOException("文件不存在：" + display(file));
            }
            long size = Files.size(file);
            int limit = readLimit();
            byte[] head;
            try (InputStream in = Files.newInputStream(file)) {
                head = in.readNBytes(BINARY_PROBE);
            }
            if (looksBinary(head)) {
                return "二进制文件，不读取内容：" + display(file) + "（" + humanSize(size) + "）。"
                        + "需要看内容请让管理员自己打开，或者只想确认它存在/大小就够用了。";
            }
            byte[] data;
            try (InputStream in = Files.newInputStream(file)) {
                data = in.readNBytes(limit);
            }
            String text = new String(data, StandardCharsets.UTF_8);
            StringBuilder result = new StringBuilder()
                    .append("文件 ").append(display(file))
                    .append("（").append(humanSize(size)).append("，上限 ").append(readMaxKb()).append(" KB）\n");
            if (size > limit) {
                result.append("…（只读了前 ").append(humanSize(limit))
                        .append("，后面的内容被截断）\n");
            }
            result.append(text);
            return result.toString();
        }
    }

    public static String write(String rawPath, String content, boolean append, boolean createParents)
            throws IOException {
        synchronized (LOCK) {
            Path file = resolve(rawPath);
            if (Files.isDirectory(file)) {
                throw new IOException("目标是目录，不能当文件写：" + display(file));
            }
            // 允许用空内容覆写来清空文件
            String body = content == null ? "" : content;
            int bytes = body.getBytes(StandardCharsets.UTF_8).length;
            int limit = writeLimit();
            if (bytes > limit) {
                throw new IOException("内容太大：" + bytes + " 字节，超过单次写入上限 "
                        + writeMaxKb() + " KB。请分多次写（mode=append）或精简内容。");
            }
            Path parent = file.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                if (!createParents) {
                    throw new IOException("父目录不存在：" + display(parent) + "（可用 op=mkdir 先建，或允许自动建目录）");
                }
                Files.createDirectories(parent);
            }
            boolean existed = Files.isRegularFile(file);
            if (append && existed) {
                Files.writeString(file, body, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, body, StandardCharsets.UTF_8);
            }
            long size = Files.size(file);
            return (existed ? "已" : "已新建并") + "写入 " + display(file)
                    + "（本次 " + humanSize(bytes) + "，模式 " + (append && existed ? "append" : "overwrite")
                    + "，现在 " + humanSize(size) + "）";
        }
    }

    public static String mkdir(String rawPath) throws IOException {
        synchronized (LOCK) {
            Path dir = resolve(rawPath);
            if (Files.isDirectory(dir)) {
                return "目录已存在：" + display(dir);
            }
            Files.createDirectories(dir);
            return "已创建目录 " + display(dir);
        }
    }

    public static String delete(String rawPath, boolean recursive) throws IOException {
        synchronized (LOCK) {
            Path target = resolve(rawPath);
            Path root = root();
            if (target.equals(root)) {
                throw new IOException("不能删除服务器根目录本身");
            }
            if (!Files.exists(target)) {
                throw new IOException("不存在：" + display(target));
            }
            ModConfig config = ConfigManager.get();
            boolean trash = config == null || config.server_tools_trash_enabled;
            String what = Files.isDirectory(target) ? "目录" : "文件";

            if (trash) {
                Path trashRoot = root.resolve(TRASH_DIR);
                Path slot = trashRoot.resolve(Instant.now().atZone(ZoneId.systemDefault()).format(STAMP));
                Path destination = slot.resolve(root.relativize(target));
                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                moveWithRetry(target, destination);
                // 回收目录本身在 server_tools_deny_paths 里，AI 移不回去（避免它擅自"撤销"自己删的东西），
                // 需要恢复时由管理员手动从回收目录搬回。
                return "已把" + what + " " + display(target) + " 移入回收目录 "
                        + display(destination) + "（需要恢复时请管理员手动移回；回收目录对 AI 不可访问）";
            }

            if (Files.isDirectory(target)) {
                if (!recursive) {
                    try (Stream<Path> stream = Files.list(target)) {
                        if (stream.findAny().isPresent()) {
                            throw new IOException("目录不为空：" + display(target)
                                    + "。确认要连内容一起删，请加 recursive=true");
                        }
                    }
                }
                deleteTree(target);
            } else {
                Files.delete(target);
            }
            return "已永久删除" + what + " " + display(target) + "（回收站已关闭，无法恢复）";
        }
    }

    public static String move(String rawPath, String rawDest) throws IOException {
        synchronized (LOCK) {
            Path from = resolve(rawPath);
            Path to = resolve(rawDest);
            if (!Files.exists(from)) {
                throw new IOException("源路径不存在：" + display(from));
            }
            if (Files.exists(to)) {
                throw new IOException("目标已存在，拒绝覆盖：" + display(to));
            }
            Path parent = to.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Files.move(from, to);
            return "已移动 " + display(from) + " → " + display(to);
        }
    }

    public static String info(String rawPath) throws IOException {
        synchronized (LOCK) {
            Path target = resolve(rawPath);
            if (!Files.exists(target)) {
                throw new IOException("不存在：" + display(target));
            }
            StringBuilder text = new StringBuilder("路径 ").append(display(target)).append('\n');
            if (Files.isDirectory(target)) {
                text.append("类型 目录\n");
                long count;
                try (Stream<Path> stream = Files.list(target)) {
                    count = stream.count();
                }
                text.append("直接子项 ").append(count).append(" 个\n");
            } else {
                text.append("类型 文件\n");
                text.append("大小 ").append(humanSize(Files.size(target))).append('\n');
            }
            text.append("修改时间 ").append(timeOf(target));
            return text.toString();
        }
    }

    public static String search(String rawPath, String pattern) throws IOException {
        synchronized (LOCK) {
            Path base = resolve(rawPath);
            if (!Files.isDirectory(base)) {
                throw new IOException("搜索起点不是目录：" + display(base));
            }
            String needle = pattern == null ? "" : pattern.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                throw new IOException("缺少 pattern（文件名关键词，支持 * 通配）");
            }
            String regex = needle.contains("*") || needle.contains("?")
                    ? ("^" + needle.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$")
                    : null;

            List<String> hits = new ArrayList<>();
            int[] visited = {0};
            boolean[] truncated = new boolean[1];
            try (Stream<Path> stream = Files.walk(base, 8)) {
                stream.forEach(candidate -> {
                    if (hits.size() >= SEARCH_LIMIT || visited[0] >= SEARCH_MAX_VISITED) {
                        truncated[0] = true;
                        return;
                    }
                    visited[0]++;
                    if (isDenied(candidate) || candidate.equals(base)) {
                        return;
                    }
                    String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
                    boolean match = regex == null ? name.contains(needle) : name.matches(regex);
                    if (!match) {
                        return;
                    }
                    String rel = display(candidate);
                    hits.add((Files.isDirectory(candidate) ? "d " : "f ") + rel);
                });
            }
            StringBuilder text = new StringBuilder()
                    .append("在 ").append(display(base)).append(" 下搜索 \"").append(pattern)
                    .append("\"，命中 ").append(hits.size()).append(" 项\n");
            for (String hit : hits) {
                text.append(hit).append('\n');
            }
            if (truncated[0]) {
                text.append("…（结果过多已截断，请给更具体的 pattern 或更小的起点目录）\n");
            }
            if (hits.isEmpty()) {
                text.append("（没有匹配）\n");
            }
            return text.toString().stripTrailing();
        }
    }

    // ---------------- 路径与安全检查 ----------------

    /** 解析相对路径并做越界 / 保护路径检查。 */
    static Path resolve(String rawPath) throws IOException {
        Path root = root();
        String cleaned = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path target = cleaned.isEmpty() ? root : root.resolve(cleaned).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("路径越界：不能跳出服务器根目录（" + rawPath + "）");
        }
        if (Files.exists(target)) {
            Path realTarget = target.toRealPath();
            Path realRoot = root.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                throw new IOException("路径越界：解析符号链接后跑到了根目录之外（" + rawPath + "）");
            }
            target = realTarget;
        }
        if (isDenied(target)) {
            throw new IOException("该路径被 server_tools_deny_paths 保护，拒绝访问：" + display(target));
        }
        return target;
    }

    /** 是否落在保护名单里（目录保护会连带其下所有内容）。 */
    static boolean isDenied(Path path) {
        ModConfig config = ConfigManager.get();
        if (config == null || config.server_tools_deny_paths == null) {
            return false;
        }
        Path root = root();
        String relative = relativeKey(path, root);
        if (relative.isEmpty()) {
            return false;
        }
        for (String raw : config.server_tools_deny_paths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String deny = raw.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
            while (deny.startsWith("/")) {
                deny = deny.substring(1);
            }
            while (deny.endsWith("/")) {
                deny = deny.substring(0, deny.length() - 1);
            }
            if (deny.isEmpty()) {
                continue;
            }
            if (relative.equals(deny) || relative.startsWith(deny + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String relativeKey(Path path, Path root) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return "";
        }
        return root.relativize(absolute).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** 给模型看的路径：相对根目录，用 / 分隔；根目录本身显示为 "." */
    static String display(Path path) {
        Path root = root();
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.equals(root)) {
            return ".";
        }
        if (!absolute.startsWith(root)) {
            return absolute.toString().replace('\\', '/');
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    // ---------------- 小工具 ----------------

    private static int readMaxKb() {
        ModConfig config = ConfigManager.get();
        return config == null || config.server_tools_read_max_kb < 1 ? 48 : config.server_tools_read_max_kb;
    }

    private static int readLimit() {
        return readMaxKb() * 1024;
    }

    private static int writeMaxKb() {
        ModConfig config = ConfigManager.get();
        return config == null || config.server_tools_write_max_kb < 1 ? 64 : config.server_tools_write_max_kb;
    }

    private static int writeLimit() {
        return writeMaxKb() * 1024;
    }

    private static int listLimit() {
        ModConfig config = ConfigManager.get();
        return config == null || config.server_tools_list_max_entries < 1
                ? 200
                : config.server_tools_list_max_entries;
    }

    private static boolean looksBinary(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        int suspicious = 0;
        for (byte value : data) {
            int b = value & 0xFF;
            if (b == 0) {
                return true;
            }
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                suspicious++;
            }
        }
        return suspicious * 100 / data.length > 10;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String timeOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant()
                    .atZone(ZoneId.systemDefault()).format(TIME);
        } catch (IOException e) {
            return "?";
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static void moveWithRetry(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(Path target) throws IOException {
        // 先删子项再删目录，避免 Files.walk 自己遍历时把要删的路径也带出来
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(target)) {
            stream.forEach(paths::add);
        }
        paths.sort(Comparator.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
        AiChatMod.LOGGER.info("永久删除：{}", display(target));
    }
}
