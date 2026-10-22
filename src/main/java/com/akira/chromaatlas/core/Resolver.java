package com.akira.chromaatlas.core;

/**
 * 单个方块的判定：给定「注册后的现状」+ 颜色表 + 配置，算出最终 mapColor id。
 *
 * <p>优先级（写死，无配置项）：
 * <ol>
 *   <li>手工覆盖表命中 ⇒ 用它，<b>无视 onlyFillNone</b>（手工值永远优先）</li>
 *   <li>命名空间被禁 ⇒ 不动</li>
 *   <li>onlyFillNone 且现状非 NONE ⇒ 不动（保护本来就有色的模组，如 chipped / Arts&amp;Crafts）</li>
 *   <li>自动表命中且合法 ⇒ 用它</li>
 *   <li>否则不动</li>
 * </ol>
 *
 * <p>返回 {@code -1} 一律表示「不要改」。真正写不写还要看 {@link AtlasConfig#dryRun}。
 */
public final class Resolver {

    public enum Reason {
        /** 命中手工覆盖表。 */
        OVERRIDE,
        /** 命中自动推断表。 */
        AUTO,
        /** 已有非 NONE 色且 onlyFillNone=true。 */
        SKIP_HAS_COLOR,
        /** 命名空间被 disabledNamespaces / 不在 enabledNamespaces 里。 */
        SKIP_NAMESPACE,
        /** 表里没有这个 id。 */
        SKIP_NO_DATA,
        /** 表里有但值非法（0 / 未使用槽位）。 */
        SKIP_INVALID_COLOR,
        /** fill-missing-model 策略下「有模型」⇒ Xaero 本来渲染得出，不补。 */
        SKIP_HAS_MODEL
    }

    public static final class Decision {
        public final String id;
        /** 注册后的当前 mapColor id（0 = NONE）。 */
        public final int current;
        /** 最终要写的 id；-1 = 不改。 */
        public final int result;
        public final Reason reason;
        /** reason == OVERRIDE 时为 true，便于统计「手工值生效了多少条」。 */
        public final boolean manual;

        Decision(String id, int current, int result, Reason reason, boolean manual) {
            this.id = id;
            this.current = current;
            this.result = result;
            this.reason = reason;
            this.manual = manual;
        }

        public boolean shouldApply() {
            return result > 0;
        }

        @Override
        public String toString() {
            return id + " cur=" + current + " -> " + result + " (" + reason + ")";
        }
    }

    private Resolver() {
    }

    public static Decision resolve(String id, int current, ColorTable table, AtlasConfig cfg) {
        Integer man = table.manualOf(id);
        if (man != null) {
            if (VanillaMapColors.exists(man)) {
                return new Decision(id, current, man, Reason.OVERRIDE, true);
            }
            return new Decision(id, current, -1, Reason.SKIP_INVALID_COLOR, true);
        }
        if (!cfg.namespaceAllowed(ColorTable.ns(id))) {
            return new Decision(id, current, -1, Reason.SKIP_NAMESPACE, false);
        }
        // force：连「本来就有色」的也覆盖（调试用）
        boolean force = "force".equals(cfg.strategy());
        if (cfg.onlyFillNone && current != 0 && !force) {
            return new Decision(id, current, -1, Reason.SKIP_HAS_COLOR, false);
        }
        Integer aut = table.autoOf(id);
        if (aut == null) {
            return new Decision(id, current, -1, Reason.SKIP_NO_DATA, false);
        }
        // fill-missing-model：只补贴图路径本来就会失败的方块（不劣化 Xaero 的贴图着色，见 R12）
        if ("fill-missing-model".equals(cfg.strategy()) && !table.isNoModel(id)) {
            return new Decision(id, current, -1, Reason.SKIP_HAS_MODEL, false);
        }
        if (!VanillaMapColors.exists(aut)) {
            return new Decision(id, current, -1, Reason.SKIP_INVALID_COLOR, false);
        }
        return new Decision(id, current, aut, Reason.AUTO, false);
    }

    /** dryRun 时是否真的写入。 */
    public static boolean shouldWrite(Decision d, AtlasConfig cfg) {
        return d.shouldApply() && !cfg.dryRun;
    }
}
