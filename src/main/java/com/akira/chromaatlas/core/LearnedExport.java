package com.akira.chromaatlas.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * M-17：把「客户端运行时学到的颜色」导出成可分发文件。
 *
 * <p>★为什么要有这个★ C 档兜底是<b>运行时</b>行为：它只在有资产可读、且方块当前是 NONE 的实例上跑。
 * 专用服务器没有 {@code assets}（原版贴图读不到，模组贴图能读但装载顺序不保证），
 * 于是「客户端学会了、服务端永远学不会」——同一套方块在两个实例上颜色不一致。
 * 导出把学习结果固化成 JSON，另一个实例放进 {@code config/chromaatlas/} 就能直接吃。
 *
 * <p>★为什么本类在 core 且不引 Gson★ 导出是纯数据变换（过滤 + 排序 + 拼 JSON），
 * 没有平台 API。放在 core 才能被 {@code SelfTest} 直接覆盖 —— 这条链的错法都是「看起来写了、其实条目被静默丢掉」，
 * 只能靠用例锁。Gson 在 fabric/neoforge 侧（{@code AtlasFiles}）读回来时用。
 *
 * <p>★为什么手写 JSON 而不是拼 Map★ 输出必须<b>字节稳定</b>：同样的缓存跑两次要得到同一个文件
 * （便于 diff / 提交 / 校验）。Gson 的默认序列化不保证键序，这里用 TreeMap + 固定缩进。
 */
public final class LearnedExport {

    /** 导出文件名（配置目录下）。 */
    public static final String FILE_NAME = "learned_overrides.json";

    /** 合法方块 id：{@code namespace:path}，两侧都只允许小写字母/数字/下划线/点/横线/斜杠。 */
    private static final Pattern ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private LearnedExport() {
    }

    /** 导出结果：JSON 文本 + 每一档被跳过的条数（日志和 /chromaatlas status 都要能说清楚「为什么少了几条」）。 */
    public static final class Result {
        public final String json;
        /** 真正写进 entries 的条数。 */
        public final int exported;
        /** 色号非法（0 / 越界）——缓存里不该有，但坏文件要显式报。 */
        public final int skippedNoColor;
        /** id 不在当前注册表（模组已卸载 / 缓存来自别的实例）。 */
        public final int skippedUnknown;
        /** 已有人工覆盖（overrides.json 优先，导出的值不该跟人抢）。 */
        public final int skippedManual;
        /** 与现有静态表同值（导出了也没有信息增量）。 */
        public final int skippedSame;
        /** 形态不合法的键（不是 {@code ns:path}）。 */
        public final List<String> invalidIds;

        Result(String json, int exported, int skippedNoColor, int skippedUnknown,
               int skippedManual, int skippedSame, List<String> invalidIds) {
            this.json = json;
            this.exported = exported;
            this.skippedNoColor = skippedNoColor;
            this.skippedUnknown = skippedUnknown;
            this.skippedManual = skippedManual;
            this.skippedSame = skippedSame;
            this.invalidIds = Collections.unmodifiableList(invalidIds);
        }

        /** 一行摘要，两个平台共用（日志 / 命令回显格式必须一致，否则对比证据时会对不上）。 */
        public String summary() {
            return "导出 " + exported
                    + "（跳过：无色 " + skippedNoColor
                    + " / 未注册 " + skippedUnknown
                    + " / 已手工覆盖 " + skippedManual
                    + " / 与表同值 " + skippedSame
                    + " / 非法键 " + invalidIds.size() + "）";
        }
    }

    /**
     * 生成导出文件内容。
     *
     * @param learned 学习结果（id → MapColor id），来自 {@code cache.json}
     * @param known   当前注册表里的 id 判定；{@code null} = 不校验（离线工具用）
     * @param manual  人工覆盖表（可为 {@code null}）
     * @param auto    静态色表（可为 {@code null}）
     * @param code    算法版本，写进文件头（换了取色算法后旧导出应当能被识别）
     */
    public static Result build(Map<String, Integer> learned,
                               java.util.function.Predicate<String> known,
                               Map<String, Integer> manual,
                               Map<String, Integer> auto,
                               String code) {
        int noColor = 0, unknown = 0, skipManual = 0, same = 0, exported = 0;
        List<String> invalid = new ArrayList<>();
        Map<String, Integer> out = new java.util.TreeMap<>();
        if (learned != null) {
            for (Map.Entry<String, Integer> e : learned.entrySet()) {
                String id = e.getKey() == null ? "" : e.getKey().trim();
                Integer c = e.getValue();
                if (id.isEmpty() || !ID.matcher(id).matches()) {
                    invalid.add(String.valueOf(e.getKey()));
                    continue;
                }
                if (c == null || !VanillaMapColors.exists(c)) {
                    noColor++;
                    continue;
                }
                if (known != null && !known.test(id)) {
                    unknown++;
                    continue;
                }
                if (manual != null && manual.containsKey(id)) {
                    skipManual++;
                    continue;
                }
                if (auto != null && c.equals(auto.get(id))) {
                    same++;
                    continue;
                }
                out.put(id, c);
                exported++;
            }
        }
        return new Result(render(out, code), exported, noColor, unknown, skipManual, same, invalid);
    }

    /** 固定格式渲染：键排序 + 两空格缩进 + 结尾换行。 */
    static String render(Map<String, Integer> entries, String code) {
        StringBuilder sb = new StringBuilder(64 + entries.size() * 32);
        sb.append("{\n");
        sb.append("  \"//\": \"ChromaAtlas learned colors — 由 /chromaatlas export-learned 生成；"
                + "删掉本文件即回到纯静态表\",\n");
        sb.append("  \"from\": \"runtime-fallback\",\n");
        sb.append("  \"code\": \"").append(escape(code == null ? "" : code)).append("\",\n");
        sb.append("  \"count\": ").append(entries.size()).append(",\n");
        sb.append("  \"entries\": {");
        if (entries.isEmpty()) {
            sb.append("}\n");
        } else {
            sb.append('\n');
            int i = 0;
            for (Map.Entry<String, Integer> e : entries.entrySet()) {
                sb.append("    \"").append(escape(e.getKey())).append("\": ").append(e.getValue());
                sb.append(++i < entries.size() ? ",\n" : "\n");
            }
            sb.append("  }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** id 字符集已受限，这里只兜底引号/反斜杠（防御性，不指望它被触发）。 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * 解析用：把「id → 色号」灌进表（平台侧调用）。返回 {@code {载入条数, 非法条数}}。
     *
     * <p>放在 core 是为了让「导出 → 读回」能在 SelfTest 里闭环，但读 JSON 仍需 Gson，
     * 所以这里只做「已知 entries 对象之后」的那一段。
     */
    public static int[] applyTo(Map<String, Integer> entries, ColorTable table) {
        int ok = 0, bad = 0;
        if (entries == null) {
            return new int[]{0, 0};
        }
        for (Map.Entry<String, Integer> e : entries.entrySet()) {
            Integer c = e.getValue();
            if (!ID.matcher(e.getKey()).matches() || c == null || !VanillaMapColors.exists(c)) {
                bad++;
                continue;
            }
            table.putLearned(e.getKey(), c);
            ok++;
        }
        return new int[]{ok, bad};
    }

    /** 便于日志：只列前 n 条。 */
    public static String head(List<String> list, int n) {
        if (list == null || list.isEmpty()) {
            return "-";
        }
        if (list.size() <= n) {
            return list.toString();
        }
        return list.subList(0, n) + " …(共 " + list.size() + " 条)";
    }

    /** 导出文件的元信息键（读回时必须跳过，否则会被当成 id 报非法）。 */
    public static boolean isMetaKey(String k) {
        return k == null || k.isEmpty() || k.startsWith("#") || k.startsWith("_")
                || k.equals("//") || k.equals("from") || k.equals("code") || k.equals("count")
                || k.equals("entries");
    }

    /** 供单测/工具用：空结果（不导出任何条目）。 */
    static Result empty(String code) {
        return new Result(render(new LinkedHashMap<>(), code), 0, 0, 0, 0, 0, new ArrayList<>());
    }
}
