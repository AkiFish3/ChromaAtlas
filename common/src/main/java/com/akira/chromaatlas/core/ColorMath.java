package com.akira.chromaatlas.core;

/**
 * 颜色空间换算。只依赖 JDK，不碰 Minecraft。
 *
 * <p>统一使用 D65 白点 + sRGB 传递函数。ΔE 用 CIE76（欧氏距离），
 * 对「吸附到 62 个离散原版色」这个粒度足够，beat 不了 CIE2000 也差不到一个色阶。
 */
public final class ColorMath {

    private ColorMath() {
    }

    /** 0xRRGGBB 拆成 [r,g,b]（0..255）。 */
    public static int[] split(int rgb) {
        return new int[]{ (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF };
    }

    public static int pack(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /** sRGB 分量（0..255）→ 线性光（0..1）。 */
    public static double toLinear(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** 0xRRGGBB → CIE L*a*b*（D65）。 */
    public static double[] rgbToLab(int rgb) {
        int[] c = split(rgb);
        double r = toLinear(c[0]);
        double g = toLinear(c[1]);
        double b = toLinear(c[2]);

        // sRGB → XYZ (D65)
        double x = r * 0.4124564 + g * 0.3575761 + b * 0.1804375;
        double y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750;
        double z = r * 0.0193339 + g * 0.1191920 + b * 0.9503041;

        // 归一化到 D65 白点
        double xn = 0.95047;
        double yn = 1.00000;
        double zn = 1.08883;

        double fx = labF(x / xn);
        double fy = labF(y / yn);
        double fz = labF(z / zn);

        return new double[]{ 116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz) };
    }

    private static double labF(double t) {
        return t > 0.008856451679035631 ? Math.cbrt(t) : (903.2962962962963 * t + 16.0) / 116.0;
    }

    /** CIE76 色差。 */
    public static double deltaE(double[] lab1, double[] lab2) {
        double dl = lab1[0] - lab2[0];
        double da = lab1[1] - lab2[1];
        double db = lab1[2] - lab2[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    public static double deltaE(int rgb1, int rgb2) {
        return deltaE(rgbToLab(rgb1), rgbToLab(rgb2));
    }
}
