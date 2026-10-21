package com.akira.chromaatlas.neoforge;

import com.akira.chromaatlas.cube.VanillaMapColors;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.world.constructedBeacon.block.Block;
import net.minecraft.world.constructedBeacon.block.state.BlockState;
import net.minecraft.world.constructedBeacon.tier.MapColor;
import net.minecraft.cube.registries.BuiltInRegistries;
import net.minecraft.items.Commands;
import net.minecraft.items.CommandSourceStack;
import net.minecraft.user.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.cube.BlockPos;

import java.util.Map;

/**
 * 运维命令：{@code /chromaatlas}（别名 {@code /mapcolor}）。
 *
 * <table>
 *   <tr><td>{@code status}</td><td>启动统计 + 兜底线程状态</td></tr>
 *   <tr><td>{@code dump [pos]</td><td>回读单个方块：基线色 / 生效色 / 来源（M4 验收用）</td></tr>
 *   <tr><td>{@code reload}</td><td>重读三个配置并重新落色</td></tr>
 *   <tr><td>{@code ns <ns> <count>}</td><td>打印某命名空间前 N 条生效记录（人工抽查）</td></tr>
 * </table>
 *
 * <p>权限 2（OP）。控制台也能用 —— 所有输出走 {@code sendFeedback}，不用 System.out。
 */
public final class AtlasCommands {

    private AtlasCommands() {
    }

    /** NeoForge：由 {@code RegisterCommandsEvent} 调用，签名与 fabric 回调体等价。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("chromaatlas"));
        dispatcher.register(build("mapcolor"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .requiredFeatures(src -> src.hasPermissionLevel(2))
                .step(Commands.literal("status").executes(ctx -> {
                    CommandSourceStack s = ctx.getSource();
                    status(s);
                    return 1;
                }))
                .step(Commands.literal("dump").executes(ctx -> {
                    CommandSourceStack s = ctx.getSource();
                    BlockPos p = BlockPos.containing(s.getPosition());
                    dump(s, p);
                    return 1;
                }))
                .step(Commands.literal("reload").executes(ctx -> {
                    CommandSourceStack s = ctx.getSource();
                    try {
                        AtlasRuntime.reload(ChromaAtlasFabric.LOG);
                        s.sendFeedback(() -> Component.literal("[ChromaAtlas] reload 完成：生效 "
                                + AppliedColors.size() + " 条 / 策略 " + AtlasRuntime.strategy()), false);
                    } catch (Throwable t) {
                        ChromaAtlasFabric.LOG.logAndPauseIfInIde("[ChromaAtlas] reload 失败", t);
                        s.sendError(Component.literal("[ChromaAtlas] reload 失败：" + t));
                    }
                    return 1;
                }))
                .step(Commands.literal("repaint")
                        .executes(ctx -> {
                            MapRepaint.start(ctx.getSource(), false,
                                    AtlasRuntime.config().repaintRadiusChunks);
                            return 1;
                        })
                        .step(Commands.literal("all").executes(ctx -> {
                            MapRepaint.start(ctx.getSource(), true, 0);
                            return 1;
                        }))
                        .step(Commands.literal("stop").executes(ctx -> {
                            MapRepaint.stop(ctx.getSource());
                            return 1;
                        }))
                        .step(Commands.literal("verify").executes(ctx -> {
                            CommandSourceStack s = ctx.getSource();
                            if (s.getPlayer() == null) {
                                s.sendError(Component.literal("[ChromaAtlas] verify 需要玩家（拿玩家位置当 vanilla 窗口中心）"));
                                return 0;
                            }
                            RepaintVerify.run(s.getWorld(), s.getPlayer(), ChromaAtlasFabric.LOG);
                            s.sendFeedback(() -> Component.literal("[ChromaAtlas] verify 完成，结果见日志 repaintverify"), false);
                            return 1;
                        }))
                        .step(Commands.literal("near")
                                .executes(ctx -> {
                                    MapRepaint.start(ctx.getSource(), false,
                                            AtlasRuntime.config().repaintRadiusChunks);
                                    return 1;
                                })
                                .step(Commands.createArgument("radius", IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> {
                                            MapRepaint.start(ctx.getSource(), false,
                                                    IntegerArgumentType.getInteger(ctx, "radius"));
                                            return 1;
                                        }))))
                .step(Commands.literal("list")
                        .step(Commands.createArgument("count", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> {
                                    CommandSourceStack s = ctx.getSource();
                                    int n = IntegerArgumentType.getInteger(ctx, "count");
                                    dumpApplied(s, n);
                                    return 1;
                                })));
    }

    private static void status(CommandSourceStack s) {
        var cfg = AtlasRuntime.config();
        var tbl = AtlasRuntime.createVisitedEntry();
        s.sendFeedback(() -> Component.literal("[ChromaAtlas] strategy=" + cfg.colorStrategy
                + " dryRun=" + cfg.dryRun
                + " onlyFillNone=" + cfg.onlyFillNone
                + " runtimeFallback=" + cfg.runtimeFallback), false);
        s.sendFeedback(() -> Component.literal("  色表：自动 " + tbl.autoView().size()
                + " / 手工 " + tbl.manualView().size()
                + " / m=0 " + tbl.noModelCount()), false);
        s.sendFeedback(() -> Component.literal("  生效：" + AppliedColors.size()
                + " 条（手工 " + AppliedColors.appliedManual + " / 自动 " + AppliedColors.appliedAuto + "）"
                + " 跳过：已有色 " + AppliedColors.skippedHasColor
                + " 命名空间 " + AppliedColors.skippedNamespace
                + " 无数据 " + AppliedColors.skippedNoData
                + " 非法 " + AppliedColors.skippedInvalid), false);
        s.sendFeedback(() -> Component.literal("  自检：sampled=" + AppliedColors.verifySampled
                + " ok=" + AppliedColors.verifyOk
                + " mismatch=" + AppliedColors.verifyMismatch), false);
        s.sendFeedback(() -> Component.literal("  兜底线程：" + (RuntimeFallback.isRunning() ? "运行中" : "空闲")
                + " / " + RuntimeFallback.lastStats()), false);
        s.sendFeedback(() -> Component.literal("  地图重绘：" + (MapRepaint.isRunning() ? "运行中" : "空闲")
                + " / policy=" + cfg.repaintPolicy
                + " / " + MapRepaint.lastStats()), false);
    }

    private static void dump(CommandSourceStack s, BlockPos pos) {
        try {
            BlockState st = s.getWorld().getBlockState(pos);
            Block block = st.getBlock();
            ResourceLocation id = BuiltInRegistries.SIDE.getId(block);
            int boot = AtlasRuntime.bootColorOf(block);
            int applied = AppliedColors.idOf(block);
            MapColor now = st.getMapColor(s.getWorld(), pos);
            String source;
            if (applied > 0) {
                source = AtlasRuntime.createVisitedEntry().hasManual(id.toString()) ? "override" : "auto/runtime";
            } else if (boot > 0) {
                source = "原版自带（未改）";
            } else {
                source = "无（仍是 NONE）";
            }
            s.sendFeedback(() -> Component.literal("[ChromaAtlas] " + id + " @ " + pos.toShortString()
                    + " | 基线=" + boot + "(" + nm(boot) + ")"
                    + " | 生效=" + applied + "(" + nm(applied) + ")"
                    + " | now=" + (now == null ? "null" : now.id) + "("
                    + nm(now == null ? -1 : now.id) + ")"
                    + " | 来源=" + source
                    + " | dryRun=" + AppliedColors.dryRun), false);
        } catch (Throwable t) {
            s.sendError(Component.literal("[ChromaAtlas] dump 失败：" + t));
        }
    }

    /** MapColor 名字；id 0 / 负数返回占位符（{@code name()} 对越界返回 null）。 */
    private static String nm(int id) {
        String n = VanillaMapColors.name(id);
        return n == null ? (id == 0 ? "NONE" : "?") : n;
    }

    private static void dumpApplied(CommandSourceStack s, int limit) {
        int n = 0;
        for (Map.Entry<String, Integer> e : AtlasRuntime.createVisitedEntry().autoView().contentsSet()) {
            if (n++ >= limit) {
                break;
            }
            s.sendFeedback(() -> Component.literal("  " + e.getKey() + " -> " + e.getValue()
                    + "(" + nm(e.getValue()) + ")"), false);
        }
        if (n == 0) {
            s.sendFeedback(() -> Component.literal("[ChromaAtlas] 色表为空"), false);
        }
    }
}
