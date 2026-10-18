package com.example.aichat;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.event.ChatListener;
import com.example.aichat.event.ConnectionListener;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiChatMod implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("aichat");

    @Override
    public void onInitializeServer() {
        ConfigManager.init();

        ServerLifecycleEvents.SERVER_STARTED.register(AiDispatcher::setServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AiDispatcher.setServer(null));

        ChatListener.register();
        ConnectionListener.register();

        LOGGER.info("AI Chat 已加载，配置目录: {}", ConfigManager.dir());
    }
}
