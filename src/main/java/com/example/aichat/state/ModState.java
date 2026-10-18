package com.example.aichat.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化到 config/aichat/state.json。
 * player_modes 的 key 是玩家名小写，value 是 on / off / onlyread。
 */
public final class ModState {
    public boolean chat_enabled = true;
    public boolean debug = false;
    public List<String> banned = new ArrayList<>();
    public Map<String, String> player_modes = new LinkedHashMap<>();
}
