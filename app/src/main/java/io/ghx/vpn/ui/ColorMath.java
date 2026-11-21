package io.ghx.vpn.ui;

import java.util.Locale;

/** Pure Java color operations, also used by the offline appearance tests. */
public final class ColorMath {
    private ColorMath() { }

    public static int opaque(int color) { return color | 0xff000000; }

    /** Only #RRGGBB / RRGGBB are accepted; alpha is deliberately not configurable. */
    public static Integer parseHex(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() != 6) return null;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F'))) return null;
        }
        try { return 0xff000000 | Integer.parseInt(value, 16); }
        catch (NumberFormatException ignored) { return null; }
    }

    public static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xffffff);
    }

    public static int blend(int from, int to, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        int r = Math.round(((from >> 16) & 255) * (1f - f) + ((to >> 16) & 255) * f);
        int g = Math.round(((from >> 8) & 255) * (1f - f) + ((to >> 8) & 255) * f);
        int b = Math.round((from & 255) * (1f - f) + (to & 255) * f);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static double linear(int component) {
        double v = component / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    public static double luminance(int color) {
        return .2126 * linear((color >> 16) & 255) + .7152 * linear((color >> 8) & 255)
                + .0722 * linear(color & 255);
    }

    public static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    /** Black or white always achieves WCAG AA normal-text contrast on opaque colors. */
    public static int onColor(int background) {
        return contrast(background, 0xff000000) >= contrast(background, 0xffffffff)
                ? 0xff000000 : 0xffffffff;
    }

    /** Retains hue where possible, adjusting toward the appropriate text pole. */
    public static int readable(int color, int background, double minimum) {
        color = opaque(color);
        if (contrast(color, background) >= minimum) return color;
        int pole = onColor(background);
        for (int step = 1; step <= 100; step++) {
            int candidate = blend(color, pole, step / 100f);
            if (contrast(candidate, background) >= minimum) return candidate;
        }
        return pole;
    }

    /** h in degrees, s/v in [0,1]. */
    public static int hsv(float hue, float saturation, float value) {
        float h = ((hue % 360f) + 360f) % 360f;
        float s = Math.max(0f, Math.min(1f, saturation));
        float v = Math.max(0f, Math.min(1f, value));
        float chroma = v * s;
        float x = chroma * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = v - chroma;
        float r = 0, g = 0, b = 0;
        if (h < 60) { r = chroma; g = x; }
        else if (h < 120) { r = x; g = chroma; }
        else if (h < 180) { g = chroma; b = x; }
        else if (h < 240) { g = x; b = chroma; }
        else if (h < 300) { r = x; b = chroma; }
        else { r = chroma; b = x; }
        return 0xff000000 | (Math.round((r + m) * 255) << 16)
                | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    public static float[] toHsv(int color) {
        float r = ((color >> 16) & 255) / 255f;
        float g = ((color >> 8) & 255) / 255f;
        float b = (color & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float delta = max - min, h = 0;
        if (delta > 0) {
            if (max == r) h = 60f * (((g - b) / delta) % 6f);
            else if (max == g) h = 60f * ((b - r) / delta + 2f);
            else h = 60f * ((r - g) / delta + 4f);
        }
        if (h < 0) h += 360f;
        return new float[]{h, max == 0 ? 0 : delta / max, max};
    }
}
