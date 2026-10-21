package com.akira.chromaatlas.core;

/**
 * 「贴图平均色 → 原版地图色 id」的吸附器。
 *
 * <p>候选集来自 {@link VanillaMapColors#usableIds()}（id 0 = CLEAR/NONE 永不参与）。
 * Lab 只算一次并缓存，因为运行时/离线工具都会重复查同几十个候选。
 */
public final class NearestMapColor {

    private static final int[] IDS = VanillaMapColors.usableIds();
    private static final double[][] LABS = new double[IDS.length][];

    static {
        for (int i = 0; i < IDS.length; i++) {
            LABS[i] = ColorMath.rgbToLab(VanillaMapColors.rgb(IDS[i]));
        }
    }

    private NearestMapColor() {
    }

    /** 返回最接近的原版 MapColor id（>=1）。候选集为空时返回 0。 */
    public static int nearest(int rgb) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        double[] target = ColorMath.rgbToLab(rgb);
        for (int i = 0; i < IDS.length; i++) {
            double d = ColorMath.deltaE(target, LABS[i]);
            if (d < bestD) {
                bestD = d;
                best = IDS[i];
            }
        }
        return best;
    }

    /** 顺带给出色差，供离线工具写「离群」报告用。 */
    public static double nearestDeltaE(int rgb) {
        double[] target = ColorMath.rgbToLab(rgb);
        double bestD = Double.MAX_VALUE;
        for (double[] lab : LABS) {
            double d = ColorMath.deltaE(target, lab);
            if (d < bestD) {
                bestD = d;
            }
        }
        return bestD;
    }

    public static int candidateCount() {
        return IDS.length;
    }
}
