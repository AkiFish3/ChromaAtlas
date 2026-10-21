package com.akira.chromaatlas.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.20.1 原版 {@code net.minecraft.block.MapColor} 全表。
 *
 * <p>由 tools/m0_mapcolor_table 从 yarn-named merged jar 的 {@code <clinit>} 逐条解析生成，
 * 不手抄：条目数 62，id 上限 61，未使用 id [62, 63]。
 * <p>id 0 = CLEAR/NONE（= 没有地图色），永不作为自动推断的候选。
 */
public final class VanillaMapColors {

    /** 槽位总数（原版 COLORS 数组长度）。 */
    public static final int SLOTS = 64;

    /** id -> 0xRRGGBB。缺少的槽位是 -1，不是黑色。 */
    private static final int[] RGB = new int[SLOTS];
    private static final String[] NAMES = new String[SLOTS];

    static {
        java.util.Arrays.fill(RGB, -1);
        RGB[0] = 0x000000; NAMES[0] = "CLEAR";
        RGB[1] = 0x7FB238; NAMES[1] = "PALE_GREEN";
        RGB[2] = 0xF7E9A3; NAMES[2] = "PALE_YELLOW";
        RGB[3] = 0xC7C7C7; NAMES[3] = "WHITE_GRAY";
        RGB[4] = 0xFF0000; NAMES[4] = "BRIGHT_RED";
        RGB[5] = 0xA0A0FF; NAMES[5] = "PALE_PURPLE";
        RGB[6] = 0xA7A7A7; NAMES[6] = "IRON_GRAY";
        RGB[7] = 0x007C00; NAMES[7] = "DARK_GREEN";
        RGB[8] = 0xFFFFFF; NAMES[8] = "WHITE";
        RGB[9] = 0xA4A8B8; NAMES[9] = "LIGHT_BLUE_GRAY";
        RGB[10] = 0x976D4D; NAMES[10] = "DIRT_BROWN";
        RGB[11] = 0x707070; NAMES[11] = "STONE_GRAY";
        RGB[12] = 0x4040FF; NAMES[12] = "WATER_BLUE";
        RGB[13] = 0x8F7748; NAMES[13] = "OAK_TAN";
        RGB[14] = 0xFFFCF5; NAMES[14] = "OFF_WHITE";
        RGB[15] = 0xD87F33; NAMES[15] = "ORANGE";
        RGB[16] = 0xB24CD8; NAMES[16] = "MAGENTA";
        RGB[17] = 0x6699D8; NAMES[17] = "LIGHT_BLUE";
        RGB[18] = 0xE5E533; NAMES[18] = "YELLOW";
        RGB[19] = 0x7FCC19; NAMES[19] = "LIME";
        RGB[20] = 0xF27FA5; NAMES[20] = "PINK";
        RGB[21] = 0x4C4C4C; NAMES[21] = "GRAY";
        RGB[22] = 0x999999; NAMES[22] = "LIGHT_GRAY";
        RGB[23] = 0x4C7F99; NAMES[23] = "CYAN";
        RGB[24] = 0x7F3FB2; NAMES[24] = "PURPLE";
        RGB[25] = 0x334CB2; NAMES[25] = "BLUE";
        RGB[26] = 0x664C33; NAMES[26] = "BROWN";
        RGB[27] = 0x667F33; NAMES[27] = "GREEN";
        RGB[28] = 0x993333; NAMES[28] = "RED";
        RGB[29] = 0x191919; NAMES[29] = "BLACK";
        RGB[30] = 0xFAEE4D; NAMES[30] = "GOLD";
        RGB[31] = 0x5CDBD5; NAMES[31] = "DIAMOND_BLUE";
        RGB[32] = 0x4A80FF; NAMES[32] = "LAPIS_BLUE";
        RGB[33] = 0x00D93A; NAMES[33] = "EMERALD_GREEN";
        RGB[34] = 0x815631; NAMES[34] = "SPRUCE_BROWN";
        RGB[35] = 0x700200; NAMES[35] = "DARK_RED";
        RGB[36] = 0xD1B1A1; NAMES[36] = "TERRACOTTA_WHITE";
        RGB[37] = 0x9F5224; NAMES[37] = "TERRACOTTA_ORANGE";
        RGB[38] = 0x95576C; NAMES[38] = "TERRACOTTA_MAGENTA";
        RGB[39] = 0x706C8A; NAMES[39] = "TERRACOTTA_LIGHT_BLUE";
        RGB[40] = 0xBA8524; NAMES[40] = "TERRACOTTA_YELLOW";
        RGB[41] = 0x677535; NAMES[41] = "TERRACOTTA_LIME";
        RGB[42] = 0xA04D4E; NAMES[42] = "TERRACOTTA_PINK";
        RGB[43] = 0x392923; NAMES[43] = "TERRACOTTA_GRAY";
        RGB[44] = 0x876B62; NAMES[44] = "TERRACOTTA_LIGHT_GRAY";
        RGB[45] = 0x575C5C; NAMES[45] = "TERRACOTTA_CYAN";
        RGB[46] = 0x7A4958; NAMES[46] = "TERRACOTTA_PURPLE";
        RGB[47] = 0x4C3E5C; NAMES[47] = "TERRACOTTA_BLUE";
        RGB[48] = 0x4C3223; NAMES[48] = "TERRACOTTA_BROWN";
        RGB[49] = 0x4C522A; NAMES[49] = "TERRACOTTA_GREEN";
        RGB[50] = 0x8E3C2E; NAMES[50] = "TERRACOTTA_RED";
        RGB[51] = 0x251610; NAMES[51] = "TERRACOTTA_BLACK";
        RGB[52] = 0xBD3031; NAMES[52] = "DULL_RED";
        RGB[53] = 0x943F61; NAMES[53] = "DULL_PINK";
        RGB[54] = 0x5C191D; NAMES[54] = "DARK_CRIMSON";
        RGB[55] = 0x167E86; NAMES[55] = "TEAL";
        RGB[56] = 0x3A8E8C; NAMES[56] = "DARK_AQUA";
        RGB[57] = 0x562C3E; NAMES[57] = "DARK_DULL_PINK";
        RGB[58] = 0x14B485; NAMES[58] = "BRIGHT_TEAL";
        RGB[59] = 0x646464; NAMES[59] = "DEEPSLATE_GRAY";
        RGB[60] = 0xD8AF93; NAMES[60] = "RAW_IRON_PINK";
        RGB[61] = 0x7FA796; NAMES[61] = "LICHEN_GREEN";
    }

    private VanillaMapColors() {
    }

    public static boolean exists(int id) {
        return id > 0 && id < SLOTS && RGB[id] >= 0;
    }

    /** 0xRRGGBB；不存在返回 -1。 */
    public static int rgb(int id) {
        return id >= 0 && id < SLOTS ? RGB[id] : -1;
    }

    public static String name(int id) {
        return id >= 0 && id < SLOTS ? NAMES[id] : null;
    }

    /** 全部可用 id（不含 0）。 */
    public static int[] usableIds() {
        int n = 0;
        for (int i = 1; i < SLOTS; i++) {
            if (RGB[i] >= 0) n++;
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 1; i < SLOTS; i++) {
            if (RGB[i] >= 0) out[k++] = i;
        }
        return out;
    }

    public static Map<Integer, String> nameMap() {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 1; i < SLOTS; i++) {
            if (RGB[i] >= 0) m.put(i, NAMES[i]);
        }
        return m;
    }
}
