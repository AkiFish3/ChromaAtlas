package com.akira.chromaatlas.fabric;

import com.akira.chromaatlas.core.LearnedExport;
import com.akira.chromaatlas.core.VanillaMapColors;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 运维命令：{@code /chromaatlas}（别名 {@code /mapcolor}）。
 *
 * <table>
 *   <tr><td>{@code status}</td><td>启动统计 + 兜底线程状态</td></tr>
 *   <tr><td>{@code dump [pos]</td><td>回读单个方块：基线色 / 生效色 / 来源（M4 验收用）</td></tr>
 *   <tr><td>{@code reload}</td><td>重读三个配置并重新落色</td></tr>
 *   <tr><td>{@code export-learned [dry]}</td><td>M-17：把 {@code cache.json} 里学到的色导出成
 *       {@code learned_overrides.json}（{@code dry} 只统计不落盘）</td></tr>
 *   <tr><td>{@code ns <ns> <count>}</td><td>打印某命名空间前 N 条生效记录（人工抽查）</td></tr>
 * </table>
 *
 * <p>权限 2（OP）。控制台也能用 —— 所有输出走 {@code sendFeedback}，不用 System.out。
 */
public final class AtlasCommands {

    private AtlasCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            dispatcher.register(build("chromaatlas"));
            dispatcher.register(build("mapcolor"));
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> build(String name) {
        return CommandManager.literal(name)
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("status").executes(ctx -> {
                    ServerCommandSource s = ctx.getSource();
                    status(s);
                    return 1;
                }))
                .then(CommandManager.literal("dump").executes(ctx -> {
                    ServerCommandSource s = ctx.getSource();
                    BlockPos p = BlockPos.ofFloored(s.getPosition());
                    dump(s, p);
                    return 1;
                }))
                .then(CommandManager.literal("reload").executes(ctx -> {
                    ServerCommandSource s = ctx.getSource();
                    try {
                        AtlasRuntime.reload(ChromaAtlasFabric.LOG);
                        s.sendFeedback(() -> Text.literal("[ChromaAtlas] reload 完成：生效 "
                                + AppliedColors.size() + " 条 / 策略 " + AtlasRuntime.strategy()), false);
                    } catch (Throwable t) {
                        ChromaAtlasFabric.LOG.error("[ChromaAtlas] reload 失败", t);
                        s.sendError(Text.literal("[ChromaAtlas] reload 失败：" + t));
                    }
                    return 1;
                }))
                .then(CommandManager.literal("export-learned")
                        .executes(ctx -> exportLearned(ctx.getSource(), false))
                        .then(CommandManager.literal("dry").executes(ctx ->
                                exportLearned(ctx.getSource(), true))))
                .then(CommandManager.literal("repaint")
                        .executes(ctx -> {
                            MapRepaint.start(ctx.getSource(), false,
                                    AtlasRuntime.config().repaintRadiusChunks);
                            return 1;
                        })
                        .then(CommandManager.literal("all").executes(ctx -> {
                            MapRepaint.start(ctx.getSource(), true, 0);
                            return 1;
                        }))
                        .then(CommandManager.literal("stop").executes(ctx -> {
                            MapRepaint.stop(ctx.getSource());
                            return 1;
                        }))
                        .then(CommandManager.literal("verify").executes(ctx -> {
                            ServerCommandSource s = ctx.getSource();
                            if (s.getPlayer() == null) {
                                s.sendError(Text.literal("[ChromaAtlas] verify 需要玩家（拿玩家位置当 vanilla 窗口中心）"));
                                return 0;
                            }
                            RepaintVerify.run(s.getWorld(), s.getPlayer(), ChromaAtlasFabric.LOG);
                            s.sendFeedback(() -> Text.literal("[ChromaAtlas] verify 完成，结果见日志 repaintverify"), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("near")
                                .executes(ctx -> {
                                    MapRepaint.start(ctx.getSource(), false,
                                            AtlasRuntime.config().repaintRadiusChunks);
                                    return 1;
                                })
                                .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> {
                                            MapRepaint.start(ctx.getSource(), false,
                                                    IntegerArgumentType.getInteger(ctx, "radius"));
                                            return 1;
                                        }))))
                .then(CommandManager.literal("list")
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> {
                                    ServerCommandSource s = ctx.getSource();
                                    int n = IntegerArgumentType.getInteger(ctx, "count");
                                    dumpApplied(s, n);
                                    return 1;
                                })));
    }

    private static void status(ServerCommandSource s) {
        var cfg = AtlasRuntime.config();
        var tbl = AtlasRuntime.table();
        s.sendFeedback(() -> Text.literal("[ChromaAtlas] strategy=" + cfg.strategy()
                + " dryRun=" + cfg.dryRun
                + " onlyFillNone=" + cfg.onlyFillNone
                + " runtimeFallback=" + cfg.runtimeFallback), false);
        s.sendFeedback(() -> Text.literal("  色表：自动 " + tbl.autoView().size()
                + " / 手工 " + tbl.manualView().size()
                + " / 学习 " + tbl.learnedCount()
                + " / m=0 " + tbl.noModelCount()), false);
        s.sendFeedback(() -> Text.literal("  生效：" + AppliedColors.size()
                + " 条（手工 " + AppliedColors.appliedManual
                + " / 自动 " + AppliedColors.appliedAuto
                + " / 学习 " + AppliedColors.appliedLearned + "）"
                + " 跳过：已有色 " + AppliedColors.skippedHasColor
                + " 命名空间 " + AppliedColors.skippedNamespace
                + " 无数据 " + AppliedColors.skippedNoData
                + " 非法 " + AppliedColors.skippedInvalid), false);
        s.sendFeedback(() -> Text.literal("  自检：sampled=" + AppliedColors.verifySampled
                + " ok=" + AppliedColors.verifyOk
                + " mismatch=" + AppliedColors.verifyMismatch), false);
        s.sendFeedback(() -> Text.literal("  兜底线程：" + (RuntimeFallback.isRunning() ? "运行中" : "空闲")
                + " / " + RuntimeFallback.lastStats()), false);
        s.sendFeedback(() -> Text.literal("  地图重绘：" + (MapRepaint.isRunning() ? "运行中" : "空闲")
                + " / policy=" + cfg.repaintPolicy
                + " / " + MapRepaint.lastStats()), false);
    }

    private static void dump(ServerCommandSource s, BlockPos pos) {
        try {
            BlockState st = s.getWorld().getBlockState(pos);
            Block block = st.getBlock();
            Identifier id = Registries.BLOCK.getId(block);
            int boot = AtlasRuntime.bootColorOf(block);
            int applied = AppliedColors.idOf(block);
            MapColor now = st.getMapColor(s.getWorld(), pos);
            String source;
            if (applied > 0) {
                if (AtlasRuntime.table().hasManual(id.toString())) {
                    source = "override";
                } else if (AtlasRuntime.table().hasLearned(id.toString())) {
                    source = "learned";
                } else {
                    source = "auto/runtime";
                }
            } else if (boot > 0) {
                source = "原版自带（未改）";
            } else {
                source = "无（仍是 NONE）";
            }
            s.sendFeedback(() -> Text.literal("[ChromaAtlas] " + id + " @ " + pos.toShortString()
                    + " | 基线=" + boot + "(" + nm(boot) + ")"
                    + " | 生效=" + applied + "(" + nm(applied) + ")"
                    + " | now=" + (now == null ? "null" : now.id) + "("
                    + nm(now == null ? -1 : now.id) + ")"
                    + " | 来源=" + source
                    + " | dryRun=" + AppliedColors.dryRun), false);
        } catch (Throwable t) {
            s.sendError(Text.literal("[ChromaAtlas] dump 失败：" + t));
        }
    }

    /**
     * M-17：把 {@code cache.json} 里学到的色导出成 {@code learned_overrides.json}。
     *
     * <p>★过滤口径都在 core 里★ 这里只负责「取数据 + 写文件」，判定（无色/未注册/已手工/与表同值）
     * 由 {@link LearnedExport#build} 做 —— 两个平台必须给出同一份结果，逻辑不能各写一遍。
     *
     * <p>★写文件必须原子★ 半截 JSON 会被下一次启动当成坏文件（虽然只是忽略），
     * 但玩家看到「导出了却没有」会当成 bug。与 {@code RuntimeCache.save} 同一套 .tmp + move。
     */
    private static int exportLearned(ServerCommandSource s, boolean dry) {
        Path gd = AtlasRuntime.gameDir();
        if (gd == null) {
            s.sendError(Text.literal("[ChromaAtlas] 运行时尚未初始化（gameDir 为空），无法导出"));
            return 0;
        }
        try {
            var cfg = AtlasRuntime.config();
            Path cacheFile = AtlasFiles.dir(gd).getParent().resolve(cfg.runtimeCacheFile);
            RuntimeCache cache = RuntimeCache.load(cacheFile, ChromaAtlasFabric.LOG);
            int[] stale = new int[1];
            Map<String, Integer> learned = cache.colorsForExport(stale);
            Set<String> known = new HashSet<>(Math.max(64, learned.size() * 2));
            for (Block b : Registries.BLOCK) {
                known.add(Registries.BLOCK.getId(b).toString());
            }
            var table = AtlasRuntime.table();
            LearnedExport.Result r = LearnedExport.build(learned, known::contains,
                    table.manualView(), table.autoView(), RuntimeCache.CODE);
            s.sendFeedback(() -> Text.literal("[ChromaAtlas] 缓存 " + cache.size() + " 条，可导出 "
                    + learned.size() + " 条（其中 " + stale[0] + " 条指纹已变，色值可能滞后于模组更新）"), false);
            s.sendFeedback(() -> Text.literal("[ChromaAtlas] " + r.summary()), false);
            if (!r.invalidIds.isEmpty()) {
                s.sendFeedback(() -> Text.literal("  非法键：" + LearnedExport.head(r.invalidIds, 10)), false);
            }
            Path out = AtlasFiles.learnedFile(gd);
            if (dry) {
                s.sendFeedback(() -> Text.literal("[ChromaAtlas] dry：未落盘（目标 " + out + "）"), false);
                return 1;
            }
            Files.createDirectories(out.getParent());
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.write(tmp, r.json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
            s.sendFeedback(() -> Text.literal("[ChromaAtlas] 已导出 -> " + out + "（" + r.exported + " 条）"), false);
            s.sendFeedback(() -> Text.literal("[ChromaAtlas] /chromaatlas reload 让它立即生效；"
                    + "搬到别的实例时放进 config/chromaatlas/ 即可"), false);
            return 1;
        } catch (Throwable t) {
            ChromaAtlasFabric.LOG.error("[ChromaAtlas] export-learned 失败", t);
            s.sendError(Text.literal("[ChromaAtlas] export-learned 失败：" + t));
            return 0;
        }
    }

    /** MapColor 名字；id 0 / 负数返回占位符（{@code name()} 对越界返回 null）。 */
    private static String nm(int id) {
        String n = VanillaMapColors.name(id);
        return n == null ? (id == 0 ? "NONE" : "?") : n;
    }

    private static void dumpApplied(ServerCommandSource s, int limit) {
        int n = 0;
        for (Map.Entry<String, Integer> e : AtlasRuntime.table().autoView().entrySet()) {
            if (n++ >= limit) {
                break;
            }
            s.sendFeedback(() -> Text.literal("  " + e.getKey() + " -> " + e.getValue()
                    + "(" + nm(e.getValue()) + ")"), false);
        }
        if (n == 0) {
            s.sendFeedback(() -> Text.literal("[ChromaAtlas] 色表为空"), false);
        }
    }
}
