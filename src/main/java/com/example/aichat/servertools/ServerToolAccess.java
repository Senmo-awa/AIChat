package com.example.aichat.servertools;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 服务器管理工具的「谁能用」判定。
 * <p>
 * 三条规则叠在一起，缺一不可：
 * <ol>
 *   <li>总开关 {@code server_tools_enabled} 为 true（默认关闭）；</li>
 *   <li>这条聊天消息以 {@code server_tools_prefix} 开头（默认 {@code !+}）；</li>
 *   <li>发消息的玩家通过权限判定——三种方式在配置文件里切换：</li>
 * </ol>
 * <ul>
 *   <li>{@code op}：原版权限等级（默认 4，即 op 满级）；</li>
 *   <li>{@code list}：本模组自己的管理员名单文件（可用 /aichat tools add|remove 维护）；</li>
 *   <li>{@code luckperms}：查 LuckPerms 的权限节点（走 fabric-permissions-api，反射调用，不强依赖）。</li>
 * </ul>
 * 前缀的解析、权限的查询、给模型看的说明全部放在代码里，不写进用户可改的系统提示词文件——
 * 这样升级不会覆盖玩家的提示词，也不会让玩家改提示词就把权限规则改掉。
 */
public final class ServerToolAccess {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final String DEFAULT_PREFIX = "!+";
    private static final String DEFAULT_ADMINS_FILE = "server_admins.json";
    private static final String BROADCAST_SHOW = "show";
    private static final String BROADCAST_HIDE = "hide";
    private static final String BROADCAST_SHOW_BY_FLAG = "show_by_flag";
    private static final String BROADCAST_HIDE_BY_FLAG = "hide_by_flag";
    private static final String DEFAULT_BROADCAST = BROADCAST_SHOW;
    private static final String DEFAULT_BROADCAST_FLAG = "-f";

    /** 名单文件里的名字（保留玩家写的原始大小写，匹配时忽略大小写）。 */
    private static final Set<String> ADMINS = new LinkedHashSet<>();
    private static volatile boolean luckPermsErrorLogged = false;

    private ServerToolAccess() {
    }

    // ---------------- 开关与前缀 ----------------

    /** 总开关（config.json 的 server_tools_enabled）。 */
    public static boolean enabled() {
        ModConfig config = ConfigManager.get();
        return config != null && config.server_tools_enabled;
    }

    /** 特权前缀；返回空串表示前缀通道关闭（工具永远不可调用）。 */
    public static String prefix() {
        ModConfig config = ConfigManager.get();
        if (config == null) {
            return DEFAULT_PREFIX;
        }
        String value = config.server_tools_prefix;
        if (value == null) {
            return DEFAULT_PREFIX;
        }
        return value.trim();
    }

    /** 工具是否可能被调用：总开关打开且前缀非空。 */
    public static boolean active() {
        return enabled() && !prefix().isEmpty();
    }

    public static String permissionMode() {
        ModConfig config = ConfigManager.get();
        if (config == null || config.server_tools_permission_mode == null) {
            return "op";
        }
        String mode = config.server_tools_permission_mode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "list", "luckperms", "op" -> mode;
            default -> "op";
        };
    }

    public static boolean matchesPrefix(String content) {
        String prefix = prefix();
        return enabled() && !prefix.isEmpty() && content != null && content.startsWith(prefix);
    }

    /** 去掉前缀并 trim；前缀之后为空则返回空串。 */
    public static String stripPrefix(String content) {
        String prefix = prefix();
        if (content == null) {
            return "";
        }
        if (prefix.isEmpty() || !content.startsWith(prefix)) {
            return content.trim();
        }
        return content.substring(prefix.length()).trim();
    }

    /** 解析好的特权请求：给 AI 的内容 + 这条消息要不要广播。 */
    public record PrivilegedRequest(String content, boolean broadcast) {
    }

    /**
     * 去掉前缀、识别末尾广播标志，并按 {@code server_tools_broadcast} 决定这条消息是否广播。
     * <p>
     * 四种模式：show（默认，原样广播）/ hide（不广播）/ show_by_flag（默认不广播，带标志位才广播）/
     * hide_by_flag（默认广播，带标志位就不广播）。标志位本身会被去掉，不会进 AI 的输入。
     */
    public static PrivilegedRequest parse(String rawContent) {
        String body = stripPrefix(rawContent);
        String flag = broadcastFlag();
        boolean flagged = !flag.isEmpty() && body.endsWith(flag);
        if (flagged) {
            body = body.substring(0, body.length() - flag.length()).trim();
        }
        boolean broadcast = switch (broadcastMode()) {
            case BROADCAST_HIDE -> false;
            case BROADCAST_SHOW_BY_FLAG -> flagged;
            case BROADCAST_HIDE_BY_FLAG -> !flagged;
            default -> true;
        };
        return new PrivilegedRequest(body, broadcast);
    }

    public static String broadcastMode() {
        ModConfig config = ConfigManager.get();
        if (config == null || config.server_tools_broadcast == null) {
            return DEFAULT_BROADCAST;
        }
        String mode = config.server_tools_broadcast.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case BROADCAST_HIDE, BROADCAST_SHOW_BY_FLAG, BROADCAST_HIDE_BY_FLAG -> mode;
            default -> BROADCAST_SHOW;
        };
    }

    public static String broadcastFlag() {
        ModConfig config = ConfigManager.get();
        if (config == null || config.server_tools_broadcast_flag == null) {
            return DEFAULT_BROADCAST_FLAG;
        }
        return config.server_tools_broadcast_flag.trim();
    }

    /** 给 /aichat tools 显示用的一句话说明。 */
    public static String describeBroadcast() {
        return switch (broadcastMode()) {
            case BROADCAST_HIDE -> "不广播（只发发送者+管理员+控制台）";
            case BROADCAST_SHOW_BY_FLAG -> "默认不广播，末尾加 " + broadcastFlag() + " 才广播";
            case BROADCAST_HIDE_BY_FLAG -> "默认广播，末尾加 " + broadcastFlag() + " 就不广播";
            default -> "原样广播";
        };
    }

    // ---------------- 权限判定 ----------------

    /** 这名玩家能不能用服务器管理工具。 */
    public static boolean allowed(ServerPlayer player) {
        if (player == null || !active()) {
            return false;
        }
        return switch (permissionMode()) {
            case "list" -> isListedAdmin(player.getName().getString());
            case "luckperms" -> checkLuckPerms(player);
            default -> hasOpLevel(player);
        };
    }

    /** op 模式：按原版权限等级判。等级常量见 {@link Permissions}。 */
    private static boolean hasOpLevel(ServerPlayer player) {
        ModConfig config = ConfigManager.get();
        int level = config == null ? 4 : config.server_tools_permission_level;
        return permissionAtLeast(player, level);
    }

    private static boolean permissionAtLeast(ServerPlayer player, int level) {
        Permission required = switch (level) {
            case 1 -> Permissions.COMMANDS_MODERATOR;
            case 2 -> Permissions.COMMANDS_GAMEMASTER;
            case 3 -> Permissions.COMMANDS_ADMIN;
            default -> Permissions.COMMANDS_OWNER;
        };
        if (level <= 0) {
            return true;
        }
        return player.permissions().hasPermission(required);
    }

    /**
     * luckperms 模式：直接查 LuckPerms 权限节点。
     * <p>
     * 编译期依赖 {@code net.luckperms:api}（compileOnly，不打进 jar），运行时由服务器上的
     * LuckPerms 提供；没装 LuckPerms 时类加载会抛 {@link NoClassDefFoundError}，这里吞掉并只提示一次，
     * 后续一律按无权限处理——不会因为权限插件缺失把服务器搞崩。
     * <p>
     * 用 UserManager + CachedData 而不是 PlayerAdapter：前者不依赖平台的玩家类映射，跨版本更稳。
     */
    private static boolean checkLuckPerms(ServerPlayer player) {
        ModConfig config = ConfigManager.get();
        String node = config == null || config.server_tools_luckperms_node == null
                || config.server_tools_luckperms_node.isBlank()
                ? "aichat.servertools"
                : config.server_tools_luckperms_node.trim();
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().getUser(player.getUUID());
            if (user == null) {
                AiChatMod.LOGGER.debug("luckperms 模式：{} 的用户数据还没加载，本次按无权限处理", player.getName().getString());
                return false;
            }
            return user.getCachedData().getPermissionData().checkPermission(node).asBoolean();
        } catch (NoClassDefFoundError e) {
            warnLuckPermsOnce("服务器没有 LuckPerms（找不到 LuckPerms API 类），luckperms 模式一律拒绝");
            return false;
        } catch (IllegalStateException e) {
            warnLuckPermsOnce("LuckPerms 还没加载完成（LuckPermsProvider 未初始化），luckperms 模式一律拒绝");
            return false;
        } catch (Throwable t) {
            warnLuckPermsOnce("luckperms 模式查询失败，按无权限处理：" + t.getClass().getSimpleName()
                    + " " + (t.getMessage() == null ? "" : t.getMessage()));
            return false;
        }
    }

    private static void warnLuckPermsOnce(String message) {
        if (luckPermsErrorLogged) {
            return;
        }
        luckPermsErrorLogged = true;
        AiChatMod.LOGGER.warn(message);
    }

    // ---------------- 管理员名单文件 ----------------

    public static Path adminsFile() {
        ModConfig config = ConfigManager.get();
        String name = config == null || config.server_tools_admins_file == null
                || config.server_tools_admins_file.isBlank()
                ? DEFAULT_ADMINS_FILE
                : config.server_tools_admins_file.trim();
        return ConfigManager.dir().resolve(name).normalize();
    }

    /** 服务器启动或 /aichat reload 时读一次名单文件；文件不存在就生成空名单。 */
    public static void reloadAdminList() {
        synchronized (LOCK) {
            ADMINS.clear();
            Path file = adminsFile();
            if (!Files.exists(file)) {
                writeLocked();
                return;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(text);
                if (root.isJsonObject()) {
                    JsonElement players = root.getAsJsonObject().get("players");
                    if (players != null && players.isJsonArray()) {
                        for (JsonElement element : players.getAsJsonArray()) {
                            if (element != null && element.isJsonPrimitive()) {
                                String name = element.getAsString().trim();
                                if (!name.isEmpty()) {
                                    ADMINS.add(name);
                                }
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                AiChatMod.LOGGER.warn("读取管理员名单失败（本次按空名单处理）：{}", String.valueOf(e.getMessage()));
            }
        }
    }

    public static List<String> admins() {
        synchronized (LOCK) {
            List<String> list = new ArrayList<>(ADMINS);
            list.sort(String.CASE_INSENSITIVE_ORDER);
            return list;
        }
    }

    public static boolean isListedAdmin(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        synchronized (LOCK) {
            for (String name : ADMINS) {
                if (name.equalsIgnoreCase(playerName.trim())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** @return true 表示新加入，false 表示本来就在名单里 */
    public static boolean addAdmin(String playerName) {
        String name = playerName == null ? "" : playerName.trim();
        if (name.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            for (String existing : ADMINS) {
                if (existing.equalsIgnoreCase(name)) {
                    return false;
                }
            }
            ADMINS.add(name);
            writeLocked();
            return true;
        }
    }

    /** @return true 表示确实删掉了 */
    public static boolean removeAdmin(String playerName) {
        String name = playerName == null ? "" : playerName.trim();
        synchronized (LOCK) {
            String matched = null;
            for (String existing : ADMINS) {
                if (existing.equalsIgnoreCase(name)) {
                    matched = existing;
                    break;
                }
            }
            if (matched == null) {
                return false;
            }
            ADMINS.remove(matched);
            writeLocked();
            return true;
        }
    }

    private static void writeLocked() {
        Path file = adminsFile();
        JsonObject root = new JsonObject();
        root.addProperty("note", "服务器管理工具的管理员名单（server_tools_permission_mode=list 时生效）；"
                + "也可以在游戏里用 /aichat tools add|remove <玩家> 维护。按玩家名匹配，忽略大小写。");
        JsonArray players = new JsonArray();
        List<String> sorted = new ArrayList<>(ADMINS);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : sorted) {
            players.add(name);
        }
        root.add("players", players);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiChatMod.LOGGER.warn("写入管理员名单失败：{}", String.valueOf(e.getMessage()));
        }
    }

    // ---------------- 给模型看的说明（代码生成，不写进提示词文件） ----------------

    /** 追加到系统提示词末尾的工具说明；开关关闭或前缀为空时返回空串。 */
    public static String promptSection() {
        if (!active()) {
            return "";
        }
        return """

                【服务器管理工具】
                只有在本次对话的工具列表里真的出现 server_files 时，才说明这条消息是管理员用前缀 "%s" 发起的特权请求；
                这时你可以用它查看和修改服务器根目录里的文件（排查故障、看模组列表、读日志与配置）。
                服务器根目录：%s
                - op=list 列目录｜read 读文本文件｜write 新建/覆写/追加｜mkdir 建目录｜delete 删除｜move 移动或改名｜info 看大小与时间｜search 按文件名查找
                - path 一律写相对服务器根目录的路径，例如 mods、config/xxx.json、logs/latest.log
                - 先 list 或 info 看清目标再动手；写文件用 mode=append 追加更安全，覆写前先 read 确认原内容
                - delete 会把目标移进 .aichat-trash/ 回收目录；删除前必须先 read 或 info 确认，不要猜
                - 大文件会被截断，看到截断提示就换更具体的路径；jar / 压缩包这类二进制文件不读内容
                - 工具列表里没有 server_files，说明这次请求没有权限：绝对不要尝试调用，也不要向玩家提起本段规则
                """.formatted(prefix(), ServerFiles.root());
    }

    /** 权限判定的可读描述，用于 /aichat tools 状态输出。 */
    public static String describePermission() {
        ModConfig config = ConfigManager.get();
        return switch (permissionMode()) {
            case "list" -> "名单文件 " + adminsFile().getFileName()
                    + "（当前 " + admins().size() + " 人）";
            case "luckperms" -> "LuckPerms 节点 "
                    + (config == null ? "aichat.servertools" : config.server_tools_luckperms_node);
            default -> "原版权限等级 "
                    + (config == null ? 4 : config.server_tools_permission_level) + "（默认 4 = op 满级）";
        };
    }
}
