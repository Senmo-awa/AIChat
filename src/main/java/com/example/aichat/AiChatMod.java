package com.example.aichat;

import com.example.aichat.command.AiChatCommand;
import com.example.aichat.event.ChatListener;
import com.example.aichat.event.ConnectionListener;
import com.example.aichat.platform.FabricPlatform;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Fabric 入口点：注册平台实现，把 Fabric 事件转给与加载器无关的 {@link AiChat}。
 */
public class AiChatMod implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        AiChat.init(FabricPlatform.INSTANCE);

        ServerLifecycleEvents.SERVER_STARTED.register(AiChat::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(AiChat::onServerStopping);

        ChatListener.register();
        ConnectionListener.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                AiChatCommand.register(dispatcher));
    }
}
