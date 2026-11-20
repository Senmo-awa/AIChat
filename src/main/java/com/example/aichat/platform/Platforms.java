package com.example.aichat.platform;

/**
 * 当前生效的 {@link Platform} 实现。
 * <p>
 * 由加载器入口点在模组初始化最早期设置（早于 {@code ConfigManager.init()}），
 * 其余代码通过 {@link #get()} 取用；没有实现时返回一个只读的「未知平台」垫片，
 * 保证单元测试或极端的加载顺序下也不会 NPE。
 */
public final class Platforms {
    private static final Platform UNKNOWN = new Platform() {
        @Override
        public java.nio.file.Path configDir() {
            return java.nio.file.Path.of("config");
        }

        @Override
        public java.nio.file.Path gameDir() {
            return java.nio.file.Path.of(".").toAbsolutePath().normalize();
        }

        @Override
        public String modVersion() {
            return "unknown";
        }

        @Override
        public String loaderName() {
            return "unknown";
        }
    };

    private static volatile Platform current = UNKNOWN;

    private Platforms() {
    }

    public static void set(Platform platform) {
        current = platform == null ? UNKNOWN : platform;
    }

    public static Platform get() {
        return current;
    }
}
