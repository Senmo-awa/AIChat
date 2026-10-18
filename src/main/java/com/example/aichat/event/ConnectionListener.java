package com.example.aichat.event;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.ai.ChatContext;
import com.example.aichat.config.ConfigManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class ConnectionListener {
    private ConnectionListener() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!ConfigManager.get().enable_join_quit_events) {
                return;
            }
            String name = handler.player.getName().getString();
            AiDispatcher.submit(new ChatContext(
                    name,
                    "join",
                    name + " 加入了游戏",
                    System.currentTimeMillis()
            ));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!ConfigManager.get().enable_join_quit_events) {
                return;
            }
            String name = handler.player.getName().getString();
            AiDispatcher.submit(new ChatContext(
                    name,
                    "quit",
                    name + " 离开了游戏",
                    System.currentTimeMillis()
            ));
        });
    }
}
