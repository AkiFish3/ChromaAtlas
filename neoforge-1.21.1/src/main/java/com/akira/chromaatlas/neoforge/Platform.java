package com.akira.chromaatlas.neoforge;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.nio.file.Path;

/**
 * 平台薄层：把 1.21.1 fabric 壳里散落的 {@code FabricLoader} 调用收成两个方法。
 *
 * <p>只做「路径」这一件事 —— 业务类（{@code AssetColorizer} / {@code RuntimeCache}）拿到根路径后
 * 的行为（目录就借用、jar 就自己开 FileSystem 并负责关）与 fabric 侧完全一致，
 * 这样 R24（关掉别人的 zipfs）的修法在两个平台上是同一份代码。
 */
public final class Platform {

    private Platform() {
    }

    /** 游戏目录（等价 {@code FabricLoader#getGameDir}）。 */
    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /**
     * 模组资源根：jar 模组返回 jar 文件路径，目录模组返回目录。
     *
     * <p>★不要在这里套 {@code getRootPaths()}★ —— NeoForge 的 IModFile 没有这个概念，
     * 而 {@code getFilePath()} 给出的正是「一个可判 isDirectory / isRegularFile 的路径」，
     * 与 fabric 侧 {@code getRootPaths().get(0)} 的语义等价。
     */
    public static Path root(String ns) {
        IModFileInfo info = ModList.get().getModFileById(ns);
        if (info == null) {
            return null;
        }
        IModFile f = info.getFile();
        return f == null ? null : f.getFilePath();
    }
}
