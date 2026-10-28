package com.example.aichat;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.command.AiChatCommand;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.event.ChatListener;
import com.example.aichat.event.ConnectionListener;
import com.example.aichat.state.StateManager;
import com.example.aichat.usage.UsageStore;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiChatMod implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("aichat");

    @Override
    public void onInitializeServer() {
        ConfigManager.init();
        StateManager.init();
        UsageStore.init();

        ServerLifecycleEvents.SERVER_STARTED.register(AiDispatcher::setServer);
        // 停服：停发一切、清缓冲、中止在途推理（否则世界保存那几秒还在烧 token，且没人看得到）
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AiDispatcher.shutdown());

        ChatListener.register();
        ConnectionListener.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                AiChatCommand.register(dispatcher));

        LOGGER.info("AI Chat v{} 已加载，配置目录: {}", version(), ConfigManager.dir());
    }

    /**
     * 启动日志带上版本号，排查「服务器到底跑的哪个 jar」时不用猜。
     */
    private static String version() {
        return FabricLoader.getInstance().getModContainer("aichat")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
