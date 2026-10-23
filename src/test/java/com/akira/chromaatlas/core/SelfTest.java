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
        testTransparencyFilter();
        testLearnedPriority();
        testLearnedExport();
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
        t.putAuto("othermod:a", 11);
        t.putAuto("minecraft:stone", 11);
        AtlasConfig cfg = new AtlasConfig();
        cfg.colorStrategy = "fill-none";   // 只测命名空间门，不测策略档
        cfg.disabledNamespaces.add("yuushya");

        eq("disabled ns skipped", -1, Resolver.resolve("yuushya:a", 0, t, cfg).result);
        eq("other ns ok", 11, Resolver.resolve("othermod:a", 0, t, cfg).result);
        // ★第 34 轮★ 原版保护默认开 ⇒ minecraft 即便不在黑名单里也不补
        eq("原版默认可保护", -1, Resolver.resolve("minecraft:stone", 0, t, cfg).result);

        AtlasConfig wl = new AtlasConfig();
        wl.colorStrategy = "fill-none";
        wl.enabledNamespaces.add("yuushya");
        eq("whitelist allows listed", 13, Resolver.resolve("yuushya:a", 0, t, wl).result);
        eq("whitelist blocks others", -1, Resolver.resolve("minecraft:stone", 0, t, wl).result);

        // 黑名单优先于白名单
        AtlasConfig both = new AtlasConfig();
        both.colorStrategy = "fill-none";
        both.enabledNamespaces.add("yuushya");
        both.disabledNamespaces.add("yuushya");
        eq("disabled wins over enabled", -1, Resolver.resolve("yuushya:a", 0, t, both).result);
    }

    private static void testOnlyFillNone() {
        ColorTable t = new ColorTable();
        t.putAuto("yuushya:a", 13);
        AtlasConfig cfg = new AtlasConfig();
        cfg.colorStrategy = "fill-none";
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
        cfg.colorStrategy = "fill-none";   // dryRun 不能落在零写入档
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
        // ★M-7 第 34 轮★ 默认 fill-none：所有地图 mod 都只能「每方块一色」，
        // 不补的结果才是黑洞；旧配置里的 auto 也必须解析成 fill-none（否则老机器永远不干活）
        eq("default strategy", "fill-none", cfg.colorStrategy);
        eq("默认策略解析", "fill-none", cfg.strategy());
        eq("auto 已废弃 ⇒ fill-none", "fill-none", new AtlasConfig().applyAuto());
        AtlasConfig ls = new AtlasConfig();
        ls.colorStrategy = "auto";
        eq("auto 配置也解析成 fill-none", "fill-none", ls.applyAuto());
        AtlasConfig fm = new AtlasConfig();
        fm.colorStrategy = "fill-missing-model";
        eq("fill-missing-model 仍可显式指定", "fill-missing-model", fm.applyAuto());

        cfg.colorStrategy = "fill-none";
        eq("fill-none fills", 11, Resolver.resolve("mod:no_model", 0, t, cfg).result);
        eq("fill-none fills has_model too", 13, Resolver.resolve("mod:has_model", 0, t, cfg).result);

        // ★第 34 轮★ 原版保护：minecraft 的 NONE 是「有意留空」（火把/铁轨/花盆），补了会糊成色块
        t.putAuto("minecraft:torch", 26);
        AtlasConfig pv = new AtlasConfig();
        pv.colorStrategy = "fill-none";
        eq("原版方块不补", -1, Resolver.resolve("minecraft:torch", 0, t, pv).result);
        pv.protectVanilla = false;
        eq("关掉 protectVanilla 才补原版", 26, Resolver.resolve("minecraft:torch", 0, t, pv).result);

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


    /** M-8：透明清单必须拦住窗/玻璃，但不能误伤普通方块；手工 overrides 仍是唯一例外。 */
    private static void testTransparencyFilter() {
        ColorTable t = new ColorTable();
        t.putAuto("coc:window_arch_black", 13);
        t.putAuto("coc:wood_planks", 13);
        t.putAuto("coc:window_arch_black_slab", 13);
        // 机制用例必须显式定策略（默认 auto 在未解析时是 fill-missing-model ⇒ 零写入）
        AtlasConfig cfg = new AtlasConfig();
        cfg.colorStrategy = "fill-none";
        TransparencyIndex idx = TransparencyIndex.of(Arrays.asList(
                "coc:window_arch_black", "coc:window_arch_black_slab", "minecraft:glass"));

        check("skipTransparent defaults on", cfg.skipTransparent);
        eq("清单条数", 3, idx.size());

        Resolver.Decision d = Resolver.resolve("coc:window_arch_black", 0, t, cfg, idx);
        eq("window refused", -1, d.result);
        eq("window reason", Resolver.Reason.REFUSED_TRANSPARENT, d.reason);
        d = Resolver.resolve("coc:window_arch_black_slab", 0, t, cfg, idx);
        eq("window slab refused too", Resolver.Reason.REFUSED_TRANSPARENT, d.reason);

        d = Resolver.resolve("coc:wood_planks", 0, t, cfg, idx);
        eq("plain block still filled", 13, d.result);

        // 关掉开关 ⇒ 恢复旧行为
        AtlasConfig off = new AtlasConfig();
        off.colorStrategy = "fill-none";
        off.skipTransparent = false;
        d = Resolver.resolve("coc:window_arch_black", 0, t, off, idx);
        eq("switch off fills window", 13, d.result);

        // 手工覆盖优先于透明清单（显式指定值永远赢）
        t.putManual("coc:window_arch_black", 26);
        d = Resolver.resolve("coc:window_arch_black", 0, t, cfg, idx);
        eq("manual override beats transparency", 26, d.result);
        eq("manual reason", Resolver.Reason.OVERRIDE, d.reason);

        // 空清单 = 旧行为（不能因为忘了装清单就把所有方块拒掉）
        eq("empty index size", 0, TransparencyIndex.EMPTY.size());
        check("empty index never skips", !TransparencyIndex.EMPTY.shouldSkip("coc:window_arch_black"));
        ColorTable plain = new ColorTable();
        plain.putAuto("coc:window_arch_black", 13);
        AtlasConfig plainCfg = new AtlasConfig();
        plainCfg.colorStrategy = "fill-none";
        d = Resolver.resolve("coc:window_arch_black", 0, plain, plainCfg, TransparencyIndex.EMPTY);
        eq("empty index fills", 13, d.result);
    }

    /**
     * M-17：learned 这一层的优先级。
     *
     * <p>锁三件事：① 静态表没有时 learned 能补；② 静态表有时 learned 不许抢；③ manual 永远最高。
     * 这三条错了的表现是「导出文件放进去了但地图没变（或改了不该改的）」—— 属于静默错，必须用例锁。
     */
    private static void testLearnedPriority() {
        AtlasConfig cfg = new AtlasConfig();
        cfg.colorStrategy = "fill-none";

        ColorTable t = new ColorTable();
        t.putLearned("coc:only_learned", 43);
        Resolver.Decision d = Resolver.resolve("coc:only_learned", 0, t, cfg);
        eq("learned fills gap", 43, d.result);
        eq("learned reason", Resolver.Reason.LEARNED, d.reason);

        // 静态表赢：同一 id 两边都有值时不该被机器猜测改掉
        t.putAuto("coc:both", 13);
        t.putLearned("coc:both", 43);
        d = Resolver.resolve("coc:both", 0, t, cfg);
        eq("auto beats learned", 13, d.result);
        eq("auto reason survives", Resolver.Reason.AUTO, d.reason);

        // 手工赢过一切
        t.putManual("coc:only_learned", 26);
        d = Resolver.resolve("coc:only_learned", 0, t, cfg);
        eq("manual beats learned", 26, d.result);

        // onlyFillNone：本来有色的方块，learned 也不许动
        ColorTable t2 = new ColorTable();
        t2.putLearned("coc:colored", 43);
        d = Resolver.resolve("coc:colored", 11, t2, cfg);
        eq("learned skipped when has color", -1, d.result);
        eq("learned skip reason", Resolver.Reason.SKIP_HAS_COLOR, d.reason);

        // 透明清单照样拦 learned（学到的色不改变「窗户不该画」的语义）
        d = Resolver.resolve("coc:colored", 0, t2, cfg,
                TransparencyIndex.of(Arrays.asList("coc:colored")));
        eq("transparent blocks learned too", Resolver.Reason.REFUSED_TRANSPARENT, d.reason);

        // learned 值非法 ⇒ 显式报 SKIP_INVALID_COLOR，不能静默当成无色
        ColorTable t3 = new ColorTable();
        t3.putLearned("coc:bad", 0);
        d = Resolver.resolve("coc:bad", 0, t3, cfg);
        eq("invalid learned rejected", Resolver.Reason.SKIP_INVALID_COLOR, d.reason);

        eq("learnedCount", 1, t3.learnedCount());
    }

    /** M-17：导出过滤 —— 每一档跳过都要有计数，不能悄悄少条目。 */
    private static void testLearnedExport() {
        java.util.Map<String, Integer> learned = new java.util.HashMap<>();
        learned.put("coc:barrel", 43);          // 正常导出
        learned.put("minia:garland_a", 5);      // 正常导出
        learned.put("gone:removed", 7);         // 未注册
        learned.put("coc:zero", 0);             // 无色
        learned.put("coc:over", 62);            // 越界
        learned.put("coc:manual", 9);           // 已手工覆盖
        learned.put("coc:same", 13);            // 与静态表同值
        learned.put("Bad Id", 5);               // 形态非法

        java.util.Map<String, Integer> manual = new java.util.HashMap<>();
        manual.put("coc:manual", 26);
        java.util.Map<String, Integer> auto = new java.util.HashMap<>();
        auto.put("coc:same", 13);

        LearnedExport.Result r = LearnedExport.build(learned, id -> !id.startsWith("gone:"),
                manual, auto, "ca-runtime-v1");
        eq("exported", 2, r.exported);
        eq("skippedNoColor", 2, r.skippedNoColor);
        eq("skippedUnknown", 1, r.skippedUnknown);
        eq("skippedManual", 1, r.skippedManual);
        eq("skippedSame", 1, r.skippedSame);
        eq("invalidIds", 1, r.invalidIds.size());
        check("json has barrel", r.json.contains("\"coc:barrel\": 43"));
        check("json has garland", r.json.contains("\"minia:garland_a\": 5"));
        check("json excludes unknown", !r.json.contains("gone:removed"));
        check("json excludes manual", !r.json.contains("coc:manual"));
        check("json excludes same-value", !r.json.contains("coc:same"));
        check("count field matches", r.json.contains("\"count\": 2,"));
        check("meta block present", r.json.contains("\"from\": \"runtime-fallback\""));
        check("ends with newline", r.json.endsWith("}\n"));

        // 元信息键必须被识别，否则读回时会被当成非法 id
        check("meta //", LearnedExport.isMetaKey("//"));
        check("meta from", LearnedExport.isMetaKey("from"));
        check("meta entries", LearnedExport.isMetaKey("entries"));
        check("real id is not meta", !LearnedExport.isMetaKey("coc:barrel"));

        // 读回：applyTo 只认合法条目
        java.util.Map<String, Integer> raw = new java.util.HashMap<>();
        raw.put("coc:barrel", 43);
        raw.put("coc:zero", 0);
        raw.put("Not An Id", 5);
        ColorTable back = new ColorTable();
        int[] st = LearnedExport.applyTo(raw, back);
        eq("applyTo ok", 1, st[0]);
        eq("applyTo bad", 2, st[1]);
        eq("roundtrip value", 43, back.learnedOf("coc:barrel"));

        // 空输入必须给出合法 JSON（不是 "entries": }）
        LearnedExport.Result empty = LearnedExport.build(new java.util.HashMap<>(), null, null, null, "x");
        eq("empty exported", 0, empty.exported);
        check("empty json valid-ish", empty.json.contains("\"entries\": {}"));
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
