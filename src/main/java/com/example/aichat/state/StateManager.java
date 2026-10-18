package com.example.aichat.state;

import com.example.aichat.AiChatMod;
import com.example.aichat.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 运行期状态（全局开关、调试、禁止列表、玩家模式）的唯一读写入口。
 * 状态变化立即落盘到 config/aichat/state.json。
 */
public final class StateManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final ModState STATE = new ModState();
    private static Path file;

    private StateManager() {
    }

    public static void init() {
        file = ConfigManager.dir().resolve("state.json");
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                ModState loaded = GSON.fromJson(json, ModState.class);
                synchronized (LOCK) {
                    if (loaded != null) {
                        STATE.chat_enabled = loaded.chat_enabled;
                        STATE.debug = loaded.debug;
                        if (loaded.banned != null) {
                            STATE.banned = loaded.banned;
                        }
                        if (loaded.player_modes != null) {
                            STATE.player_modes = loaded.player_modes;
                        }
                    }
                }
            } catch (IOException | JsonParseException e) {
                AiChatMod.LOGGER.warn("读取 state.json 失败，使用默认状态: {}", e.getMessage());
            }
        }
        synchronized (LOCK) {
            saveLocked();
        }
    }

    public static boolean isChatEnabled() {
        synchronized (LOCK) {
            return STATE.chat_enabled;
        }
    }

    public static void setChatEnabled(boolean enabled) {
        synchronized (LOCK) {
            STATE.chat_enabled = enabled;
            saveLocked();
        }
    }

    public static boolean isDebug() {
        synchronized (LOCK) {
            return STATE.debug;
        }
    }

    public static boolean toggleDebug() {
        synchronized (LOCK) {
            STATE.debug = !STATE.debug;
            saveLocked();
            return STATE.debug;
        }
    }

    public static PlayerMode getMode(String playerName) {
        synchronized (LOCK) {
            return PlayerMode.parse(STATE.player_modes.get(key(playerName)));
        }
    }

    public static void setMode(String playerName, PlayerMode mode) {
        String key = key(playerName);
        synchronized (LOCK) {
            if (mode == PlayerMode.ON) {
                STATE.player_modes.remove(key);
            } else {
                STATE.player_modes.put(key, mode.id());
            }
            saveLocked();
        }
    }

    public static boolean isBanned(String playerName) {
        String key = key(playerName);
        synchronized (LOCK) {
            return isBannedLocked(key);
        }
    }

    public static boolean ban(String playerName) {
        String key = key(playerName);
        synchronized (LOCK) {
            if (isBannedLocked(key)) {
                return false;
            }
            STATE.banned.add(playerName);
            saveLocked();
            return true;
        }
    }

    public static boolean unban(String playerName) {
        String key = key(playerName);
        synchronized (LOCK) {
            for (int i = 0; i < STATE.banned.size(); i++) {
                if (key(STATE.banned.get(i)).equals(key)) {
                    STATE.banned.remove(i);
                    saveLocked();
                    return true;
                }
            }
            return false;
        }
    }

    public static List<String> bannedList() {
        synchronized (LOCK) {
            return new ArrayList<>(STATE.banned);
        }
    }

    /**
     * 消息是否应该发给 AI：全局开启、未被 ban、玩家模式为 ON。
     */
    public static boolean canReplyTo(String playerName) {
        String key = key(playerName);
        synchronized (LOCK) {
            if (!STATE.chat_enabled || isBannedLocked(key)) {
                return false;
            }
            return PlayerMode.parse(STATE.player_modes.get(key)) == PlayerMode.ON;
        }
    }

    /**
     * 玩家是否能看见 AI 广播：只有 OFF 会被屏蔽。
     */
    public static boolean canReceiveFromAi(String playerName) {
        synchronized (LOCK) {
            return PlayerMode.parse(STATE.player_modes.get(key(playerName))) != PlayerMode.OFF;
        }
    }

    private static boolean isBannedLocked(String key) {
        for (String name : STATE.banned) {
            if (key(name).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String key(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    private static void saveLocked() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(STATE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AiChatMod.LOGGER.warn("写入 state.json 失败: {}", e.getMessage());
        }
    }
}
