package com.example.aichat;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.command.AiChatCommand;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
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
        logEffectiveConfig();
    }

    /**
     * 启动时把「配置写了到底生不生效」的判定结果打出来。
     * <p>
     * 起因：改了忽略前缀却没生效，得发一条 {@code !!} 试探才知道；有了这几行，
     * 换完 jar 看一眼启动日志就能确认——版本、生效的忽略前缀、工具开关、缓冲与模型。
     */
    private static void logEffectiveConfig() {
        ModConfig config = ConfigManager.get();
        if (config == null) {
            LOGGER.warn("配置未加载，AI Chat 本次不会响应任何消息");
            return;
        }
        LOGGER.info("忽略前缀: {}（以这些前缀开头的聊天不送 AI；[] = 不忽略）",
                config.ignored_chat_prefixes == null ? "[]" : config.ignored_chat_prefixes);
        LOGGER.info("服务器管理工具: {}，特权前缀: \"{}\"，权限模式: {}",
                config.server_tools_enabled ? "开" : "关",
                config.server_tools_prefix,
                config.server_tools_permission_mode);
        LOGGER.info("缓冲: {}（{} 秒 / {} 条 / {} 字符），模型: {}，工具: {}",
                config.buffer_enabled ? "开" : "关",
                Math.max(1, config.buffer_delay_seconds),
                config.buffer_max_messages,
                config.buffer_max_chars,
                config.model,
                config.enable_tools ? "开" : "关");
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
