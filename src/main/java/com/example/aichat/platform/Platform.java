package com.example.aichat.platform;

import java.nio.file.Path;

/**
 * 模组与「加载器 / 游戏」之间的最小接口。
 * <p>
 * 本模组同时发布 Fabric 与 NeoForge 两个版本，公共逻辑只认这个接口，不直接引用任何
 * 加载器 API；每个加载器各有一份实现（{@code FabricPlatform} / {@code NeoForgePlatform}），
 * 在入口点里注册到 {@link Platforms}。
 */
public interface Platform {
    /** 配置目录（相当于 {@code config/}）。 */
    Path configDir();

    /** 服务器运行目录（相当于 {@code .}，server.properties 所在目录）。 */
    Path gameDir();

    /** 模组版本号，例如 {@code 2.3.0}；取不到时返回 {@code unknown}。 */
    String modVersion();

    /** 加载器名称，仅用于日志（{@code Fabric} / {@code NeoForge}）。 */
    String loaderName();
}
