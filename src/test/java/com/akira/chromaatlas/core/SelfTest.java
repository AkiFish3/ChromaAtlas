package com.akira.chromaatlas.core;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * core 自检。不引 JUnit（工程里首次构建前不想拉依赖），用 main + 退出码。
 *
 * <p>跑法（JDK 17+）：
 * <pre>
 *   javac -d build/core $(find core/src/main/java -name '*.java')
 *   javac -cp build/core -d build/core $(find core/src/test/java -name '*.java')
 *   java -cp build/core com.akira.chromaatlas.core.SelfTest
 * </pre>
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testVanillaTable();
        testNearestSelfConsistency();
        testNearestKnown();
        testResolverPriority();
        testNamespaceGate();
        testOnlyFillNone();
        testInvalidValues();
        testDryRun();
        testColorStrategy();
        testAutomationDefaults();
        testSnapshot();

        System.out.println("SELFTEST passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------- 断言工具 ----------

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failed++;
            System.out.println("FAIL " + name + " expected=" + expected + " actual=" + actual);
        } else {
            passed++;
        }
    }

    // ---------- 用例 ----------

    private static void testVanillaTable() {
        int[] ids = VanillaMapColors.usableIds();
        eq("usable count (0..61 minus CLEAR)", 61, ids.length);
        eq("CLEAR id0 rgb", 0, VanillaMapColors.rgb(0));
        eq("PALE_GREEN rgb", 0x7FB238, VanillaMapColors.rgb(1));
        eq("LICHEN_GREEN rgb", 0x7FA796, VanillaMapColors.rgb(61));
        check("id 62 unused", !VanillaMapColors.exists(62));
        check("id 999 out of range", !VanillaMapColors.exists(999));
        eq("name(11)", "STONE_GRAY", VanillaMapColors.name(11));
    }

    /** 每个候选色吸附回自己 —— 顺带验证 Lab 换算没写反。 */
    private static void testNearestSelfConsistency() {
        int bad = 0;
        for (int id : VanillaMapColors.usableIds()) {
            int back = NearestMapColor.nearest(VanillaMapColors.rgb(id));
            if (back != id) {
                bad++;
                System.out.println("  self-map miss: " + id + " (" + VanillaMapColors.name(id)
                        + ") -> " + back + " (" + VanillaMapColors.name(back) + ")");
            }
        }
        eq("nearest(x)==x for all 61", 0, bad);
    }

    private static void testNearestKnown() {
        eq("pure white -> WHITE(8)", 8, NearestMapColor.nearest(0xFFFFFF));
        eq("pure black -> BLACK(29)", 29, NearestMapColor.nearest(0x000000));
        eq("pure red -> BRIGHT_RED(4)", 4, NearestMapColor.nearest(0xFF0000));
        // 深色木板这一类是实际场景（coc/minia 大量木方块），要求在木色带上
        int wood = NearestMapColor.nearest(0x8B6A4A);
        String n = VanillaMapColors.name(wood);
        check("mid wood 0x8B6A4A lands in wood family, got " + n,
                "OAK_TAN".equals(n) || "DIRT_BROWN".equals(n) || "SPRUCE_BROWN".equals(n));
        check("nearestDeltaE small for exact color", NearestMapColor.nearestDeltaE(0x7FB238) < 0.001);
    }

    private static void testResolverPriority() {
        ColorTable t = new ColorTable();
        t.putAuto("yuushya:a", 13);
        t.putManual("yuushya:a", 26);
        AtlasConfig cfg = new AtlasConfig();

        Resolver.Decision d = Resolver.resolve("yuushya:a", 0, t, cfg);
        eq("override beats auto", 26, d.result);
        eq("override reason", Resolver.Reason.OVERRIDE, d.reason);

        // 手工覆盖必须能改「本来就有色」的方块
        d = Resolver.resolve("yuushya:a", 11, t, cfg);
        eq("override applies even when has color", 26, d.result);

        // 自动值不得改已有色
        ColorTable t2 = new ColorTable();
        t2.putAuto("yuushya:b", 13);
        d = Resolver.resolve("yuushya:b", 11, t2, cfg);
        eq("auto skipped when has color", -1, d.result);
        eq("skip reason", Resolver.Reason.SKIP_HAS_COLOR, d.reason);
        check("shouldApply false", !d.shouldApply());

        d = Resolver.resolve("yuushya:missing", 0, t2, cfg);
        eq("no data -> -1", -1, d.result);
        eq("no data reason", Resolver.Reason.SKIP_NO_DATA, d.reason);
    }

    private static void testNamespaceGate() {
        ColorTable t = new ColorTable();
        t.putAuto("yuushya:a", 13);
        t.putAuto("minecraft:stone", 11);
        AtlasConfig cfg = new AtlasConfig();
        cfg.disabledNamespaces.add("yuushya");

        eq("disabled ns skipped", -1, Resolver.resolve("yuushya:a", 0, t, cfg).result);
        eq("other ns ok", 11, Resolver.resolve("minecraft:stone", 0, t, cfg).result);

        AtlasConfig wl = new AtlasConfig();
        wl.enabledNamespaces.add("yuushya");
        eq("whitelist allows listed", 13, Resolver.resolve("yuushya:a", 0, t, wl).result);
        eq("whitelist blocks others", -1, Resolver.resolve("minecraft:stone", 0, t, wl).result);

        // 黑名单优先于白名单
        AtlasConfig both = new AtlasConfig();
        both.enabledNamespaces.add("yuushya");
        both.disabledNamespaces.add("yuushya");
        eq("disabled wins over enabled", -1, Resolver.resolve("yuushya:a", 0, t, both).result);
    }

    private static void testOnlyFillNone() {
        ColorTable t = new ColorTable();
        t.putAuto("yuushya:a", 13);
        AtlasConfig cfg = new AtlasConfig();
        cfg.onlyFillNone = false;
        eq("onlyFillNone=false rewrites colored block", 13,
                Resolver.resolve("yuushya:a", 11, t, cfg).result);
        cfg.onlyFillNone = true;
        eq("onlyFillNone=true protects colored block", -1,
                Resolver.resolve("yuushya:a", 11, t, cfg).result);
    }

    private static void testInvalidValues() {
        ColorTable t = new ColorTable();
        t.putAuto("a:x", 0);      // NONE 不是合法目标
        t.putAuto("a:y", 62);     // 未使用槽位
        t.putManual("a:z", 0);
        AtlasConfig cfg = new AtlasConfig();
        eq("auto 0 invalid", -1, Resolver.resolve("a:x", 0, t, cfg).result);
        eq("auto 62 invalid", -1, Resolver.resolve("a:y", 0, t, cfg).result);
        eq("manual 0 invalid", Resolver.Reason.SKIP_INVALID_COLOR,
                Resolver.resolve("a:z", 0, t, cfg).reason);

        eq("invalidColorIds count", 3, t.invalidColorIds().size());
        eq("unknownIds finds all", 3,
                t.unknownIds(new LinkedHashSet<>(Arrays.asList("b:other"))).size());
        eq("unknownIds empty when known", 0,
                t.unknownIds(new LinkedHashSet<>(Arrays.asList("a:x", "a:y", "a:z"))).size());

        // M8：手工表与自动表的「未知 id」必须分开算 —— 自动表在开发实例里天然有一大批本实例没装的条目
        java.util.Set<String> known = new LinkedHashSet<>(Arrays.asList("a:x"));
        eq("unknownManualIds only manual", 1, t.unknownManualIds(known).size());
        eq("unknownManualIds value", "a:z", t.unknownManualIds(known).get(0));
        eq("unknownAutoCount only auto", 1, t.unknownAutoCount(known));
        eq("invalidManualIds only manual", 1, t.invalidManualIds().size());
        eq("invalidManualIds value has id", true, t.invalidManualIds().get(0).startsWith("a:z="));
        eq("MAX_ID is 61", 61, VanillaMapColors.MAX_ID);
        eq("exists(61)", true, VanillaMapColors.exists(61));
        eq("exists(62) false", false, VanillaMapColors.exists(62));
    }

    private static void testDryRun() {
        ColorTable t = new ColorTable();
        t.putAuto("yuushya:a", 13);
        AtlasConfig cfg = new AtlasConfig();
        Resolver.Decision d = Resolver.resolve("yuushya:a", 0, t, cfg);
        check("writes when not dry", Resolver.shouldWrite(d, cfg));
        cfg.dryRun = true;
        check("dryRun suppresses write", !Resolver.shouldWrite(d, cfg));
        check("decision still recorded in dryRun", d.shouldApply());
    }

    /**
     * M4 新增的 colorStrategy 三级（§1.5）：
     * fill-none 补全部 NONE / fill-missing-model 只补 m=0 / force 连有色也覆盖。
     */
    private static void testColorStrategy() {
        ColorTable t = new ColorTable();
        t.putAuto("mod:no_model", 11);
        t.putNoModel("mod:no_model");
        t.putAuto("mod:has_model", 13);

        AtlasConfig cfg = new AtlasConfig();
        // M9 改档：默认必须是「零降级」的 fill-missing-model（Xaero 会对能采样的方块自己按贴图着色）
        eq("default strategy", "fill-missing-model", cfg.colorStrategy);

        cfg.colorStrategy = "fill-none";
        eq("fill-none fills", 11, Resolver.resolve("mod:no_model", 0, t, cfg).result);
        eq("fill-none fills has_model too", 13, Resolver.resolve("mod:has_model", 0, t, cfg).result);

        cfg.colorStrategy = "fill-missing-model";
        eq("missing-model fills m=0", 11, Resolver.resolve("mod:no_model", 0, t, cfg).result);
        Resolver.Decision d = Resolver.resolve("mod:has_model", 0, t, cfg);
        eq("missing-model skips m=1", -1, d.result);
        eq("missing-model reason", Resolver.Reason.SKIP_HAS_MODEL, d.reason);

        cfg.colorStrategy = "force";
        eq("force overwrites existing color", 13, Resolver.resolve("mod:has_model", 11, t, cfg).result);
        eq("force still respects namespace", -1,
                Resolver.resolve("mod:has_model", 11, t, disabledNamespaceConfig("mod")).result);
    }

    private static AtlasConfig disabledNamespaceConfig(String ns) {
        AtlasConfig c = new AtlasConfig();
        c.colorStrategy = "force";
        c.disabledNamespaces.add(ns);
        return c;
    }

    /** B/C 档自动化的默认口径（2026-10-22 Aki 要求「装完就忘」）。 */
    private static void testAutomationDefaults() {
        AtlasConfig cfg = new AtlasConfig();
        check("runtimeFallback defaults on", cfg.runtimeFallback);
        // R15：写回 Properties.mapColor 已被 javap 证伪 ⇒ 默认关
        check("writeBackProperties defaults off", !cfg.writeBackProperties);
        check("mixinGetMapColor defaults on", cfg.mixinGetMapColor);
        check("dryRun defaults off", !cfg.dryRun);
        check("onlyFillNone defaults on", cfg.onlyFillNone);
        check("verbose defaults off", !cfg.verbose);
        check("cache path is namespaced",
                cfg.runtimeCacheFile.startsWith("chromaatlas/"));
        eq("fallback batch size", 64, cfg.fallbackBatchSize);
        check("fallback is rate limited", cfg.fallbackSleepMs > 0);
        // 关掉兜底后，表里没有的方块一律不动（不能因为"能算"就偷偷算）
        cfg.runtimeFallback = false;
        ColorTable t = new ColorTable();
        Resolver.Decision d = Resolver.resolve("x:y", 0, t, cfg);
        eq("no fallback -> skip", -1, d.result);
        // 兜底是平台侧在 resolve 之前做的：resolve 只负责报告「表里没有」
        eq("no data reason stays SKIP_NO_DATA", Resolver.Reason.SKIP_NO_DATA, d.reason);
    }

    /** 热路径快照：语义 + 1e6 次查询耗时（M3b 验收要求 < 50ms）。 */
    private static void testSnapshot() {
        ColorSnapshot snap = new ColorSnapshot();
        eq("empty lookup", -1, snap.lookup("a:b"));
        eq("empty size", 0, snap.size());

        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < 6000; i++) {
            m.put("ns:block_" + i, (i % 61) + 1);
        }
        snap.replaceAuto(m);

        eq("size after replace", 6000, snap.size());
        eq("hit", 1, snap.lookup("ns:block_0"));
        eq("miss", -1, snap.lookup("ns:nope"));
        eq("null id is a miss, not an NPE", -1, snap.lookup(null));

        // 调用方后续改原 map 不得影响快照（必须是副本）
        m.put("ns:block_0", 61);
        eq("snapshot is a copy", 1, snap.lookup("ns:block_0"));

        // 增量合并
        java.util.Map<String, Integer> delta = new java.util.HashMap<>();
        delta.put("ns:new_one", 29);
        snap.mergeAuto(delta);
        eq("merge adds", 29, snap.lookup("ns:new_one"));
        eq("merge keeps old", 2, snap.lookup("ns:block_1"));
        eq("merge size", 6001, snap.size());

        // 热路径基准：1e6 次查询
        String[] ids = new String[1024];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "ns:block_" + (i * 5 % 6000);
        }
        long sink = 0;
        for (int i = 0; i < 100_000; i++) {          // 预热
            sink += snap.lookup(ids[i & 1023]);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) {
            sink += snap.lookup(ids[i & 1023]);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  snapshot 1e6 lookups = " + ms + " ms (sink=" + (sink == 0) + ")");
        check("1e6 lookups < 50ms, got " + ms, ms < 50);
    }
}
