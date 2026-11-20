package com.example.aichat;

import com.example.aichat.command.AiChatCommand;
import com.example.aichat.event.ChatHandler;
import com.example.aichat.event.ConnectionHandler;
import com.example.aichat.platform.NeoForgePlatform;
import com.example.aichat.servertools.ServerToolAccess;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * NeoForge 入口点：注册平台实现，把 NeoForge 事件转给与加载器无关的 {@link AiChat}。
 */
@Mod(AiChatNeoForge.MOD_ID)
public final class AiChatNeoForge {
    public static final String MOD_ID = "aichat";

    public AiChatNeoForge(IEventBus modBus) {
        AiChat.init(NeoForgePlatform.INSTANCE);

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerQuit);
    }

    private void onServerStarted(ServerStartedEvent event) {
        AiChat.onServerStarted(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        AiChat.onServerStopping(event.getServer());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        AiChatCommand.register(event.getDispatcher());
    }

    /**
     * 聊天事件：NeoForge 只有一个可取消的事件，所以「要不要广播」与「要不要送 AI」
     * 在同一个回调里一次判定完。
     */
    private void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String raw = event.getRawText();
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (ServerToolAccess.matchesPrefix(raw)) {
            if (ChatHandler.handlePrivileged(player, raw) == ChatHandler.Action.SUPPRESS) {
                event.setCanceled(true);
            }
            return;
        }
        ChatHandler.handleChat(player, raw);
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConnectionHandler.playerJoined(player, player.level().getServer());
        }
    }

    private void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConnectionHandler.playerLeft(player, player.level().getServer());
        }
    }
}
