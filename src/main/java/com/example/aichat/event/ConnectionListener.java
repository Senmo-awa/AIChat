package com.example.aichat.event;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Fabric 侧的玩家进出事件注册。 */
public final class ConnectionListener {
    private ConnectionListener() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ConnectionHandler.playerJoined(handler.player, server));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ConnectionHandler.playerLeft(handler.player, server));
    }
}
