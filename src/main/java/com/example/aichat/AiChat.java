package com.example.aichat;

import com.example.aichat.ai.AiDispatcher;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.config.ModConfig;
import com.example.aichat.platform.Platform;
import com.example.aichat.platform.Platforms;
import com.example.aichat.state.StateManager;
import com.example.aichat.usage.UsageStore;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 与加载器无关的模组本体：初始化顺序、服务器生命周期钩子、启动自检日志。
 * <p>
 * 每个加载器各有一个很薄的入口点（Fabric 的 {@code AiChatMod}、NeoForge 的
 * {@code AiChatNeoForge}），只负责「注册平台实现 + 把加载器事件转给这里」。
 */
public final class AiChat {
    public static final Logger LOGGER = LoggerFactory.getLogger("aichat");

    private AiChat() {
    }

    /**
     * 初始化配置、状态与用量账本，并打印启动自检日志。
     * 必须由加载器入口点在注册事件之前调用一次。
     */
    public static void init(Platform platform) {
        Platforms.set(platform);
        ConfigManager.init();
        StateManager.init();
        UsageStore.init();
        LOGGER.info("AI Chat v{} 已加载（{}），配置目录: {}",
                platform.modVersion(), loaderName(), ConfigManager.dir());
        logEffectiveConfig();
    }

    /** 服务器启动完成：开始允许向 AI 提交事件。 */
    public static void onServerStarted(MinecraftServer server) {
        AiDispatcher.setServer(server);
    }

    /**
     * 服务器开始关闭：停发一切、清缓冲、中止在途推理。
     * 停服那几秒玩家会成批断线，quit 事件毫无意义，必须整批丢掉。
     */
    public static void onServerStopping(MinecraftServer server) {
        AiDispatcher.shutdown();
    }

    /**
     * 启动时把「配置写了到底生不生效」的判定结果打出来。
     * <p>
     * 起因：改了屏蔽规则却没生效，得发一条消息试探才知道；有了这几行，
     * 换完 jar 看一眼启动日志就能确认——版本、生效的屏蔽规则、工具开关、缓冲与模型。
     */
    private static void logEffectiveConfig() {
        ModConfig config = ConfigManager.get();
        if (config == null) {
            LOGGER.warn("配置未加载，AI Chat 本次不会响应任何消息");
            return;
        }
        LOGGER.info("屏蔽规则：消息 {} 条 / 玩家 {} 条（正则，不区分大小写）。{}（空数组 = 不屏蔽）",
                ruleCount(config.blocked_message_patterns),
                ruleCount(config.blocked_player_patterns),
                ConfigManager.BLOCK_NOTICE);
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

    private static int ruleCount(java.util.List<String> rules) {
        return rules == null ? 0 : rules.size();
    }

    /** 日志里标出当前加载器，排查「装的是哪个平台的包」时一眼能看出。 */
    private static String loaderName() {
        return Platforms.get().loaderName();
    }
}
