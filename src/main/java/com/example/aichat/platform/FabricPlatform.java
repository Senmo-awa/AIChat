package com.example.aichat.platform;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Fabric 平台实现：目录与版本号都来自 Fabric Loader。 */
public final class FabricPlatform implements Platform {
    public static final FabricPlatform INSTANCE = new FabricPlatform();

    private FabricPlatform() {
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer("aichat")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }
}
