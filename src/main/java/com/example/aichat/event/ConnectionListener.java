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
            if (!ConfigManager.get().enable_join_quit_events || stopped(server)) {
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
            // 停服时每个在线玩家都会触发一次 quit：这些事件毫无意义（玩家是被服务器踢的，
            // 也没人能看到回复），必须整批丢掉，否则一次 stop 就是十几条 API 请求。
            if (!ConfigManager.get().enable_join_quit_events || stopped(server)) {
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

    /** 停服进行中（或已收到 stop）：不再往 AI 提交任何事件。 */
    private static boolean stopped(net.minecraft.server.MinecraftServer server) {
        return AiDispatcher.isShuttingDown() || server == null || !server.isRunning();
    }
}
