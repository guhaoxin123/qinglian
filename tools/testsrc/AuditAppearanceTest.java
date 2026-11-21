import io.ghx.vpn.ui.ColorMath;

/** Pure Java, offline. Compile with ColorMath.java; no Android runtime or device required. */
public final class AuditAppearanceTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void same(int actual, int expected, String message) {
        check(actual == expected, message + ": " + ColorMath.hex(actual) + " != " + ColorMath.hex(expected));
    }
    public static void main(String[] args) {
        same(ColorMath.parseHex("#000000"), 0xff000000, "Black Hex");
        same(ColorMath.parseHex("ffffff"), 0xffffffff, "White Hex without hash");
        same(ColorMath.parseHex(" #f4b89a "), 0xfff4b89a, "Peach Hex case/whitespace");
        same(ColorMath.parseHex("#3D7EFF"), 0xff3d7eff, "Existing default blue");
        String[] invalid = {null, "", "#", "#123", "#12345", "#1234567", "#FFFFFFFF",
                "GGGGGG", "-00001", "+00000", "0xFFFF", "##123456", "12 456", "１２３４５６"};
        for (String input : invalid) check(ColorMath.parseHex(input) == null, "Reject invalid Hex " + input);
        check("#00ABEF".equals(ColorMath.hex(0x1200abef)), "Stable six-digit opaque formatting");
        same(ColorMath.opaque(0x003d7eff), 0xff3d7eff, "Opaque colors");
        same(ColorMath.hsv(0, 1, 1), 0xffff0000, "HSV red");
        same(ColorMath.hsv(60, 1, 1), 0xffffff00, "HSV yellow");
        same(ColorMath.hsv(120, 1, 1), 0xff00ff00, "HSV green");
        same(ColorMath.hsv(180, 1, 1), 0xff00ffff, "HSV cyan");
        same(ColorMath.hsv(240, 1, 1), 0xff0000ff, "HSV blue");
        same(ColorMath.hsv(300, 1, 1), 0xffff00ff, "HSV magenta");
        same(ColorMath.hsv(360, 1, 1), 0xffff0000, "Hue wrap");
        same(ColorMath.hsv(-60, 1, 1), 0xffff00ff, "Negative hue wrap");
        same(ColorMath.hsv(25, 0, 1), 0xffffffff, "Zero saturation white");
        same(ColorMath.hsv(25, 1, 0), 0xff000000, "Zero brightness black");
        same(ColorMath.blend(0xff000000, 0xffffffff, .5f), 0xff808080, "Blend midpoint");
        same(ColorMath.blend(0xff000000, 0xffffffff, -1), 0xff000000, "Blend clamps low");
        same(ColorMath.blend(0xff000000, 0xffffffff, 2), 0xffffffff, "Blend clamps high");
        same(ColorMath.onColor(0xff000000), 0xffffffff, "White text on black accent");
        same(ColorMath.onColor(0xffffffff), 0xff000000, "Black text on white accent");
        check(Math.abs(ColorMath.contrast(0xff000000, 0xffffffff) - 21) < .0001,
                "WCAG contrast black/white");
        // RGB/HSV synchronization plus arbitrary-accent foreground and gradient accessibility.
        for (int r = 0; r <= 255; r += 17) {
            for (int g = 0; g <= 255; g += 17) {
                for (int b = 0; b <= 255; b += 17) {
                    int color = 0xff000000 | (r << 16) | (g << 8) | b;
                    float[] hsv = ColorMath.toHsv(color);
                    same(ColorMath.hsv(hsv[0], hsv[1], hsv[2]), color, "RGB/HSV round-trip");
                    int foreground = ColorMath.onColor(color);
                    check(ColorMath.contrast(color, foreground) >= 4.5, "AA onAccent for " + ColorMath.hex(color));
                    int second = ColorMath.blend(color, foreground == 0xff000000 ? 0xffffffff : 0xff000000, .08f);
                    check(ColorMath.contrast(second, foreground) >= 4.5, "AA gradient endpoint");
                    int darkSurface = ColorMath.blend(0xff302e34, color, .06f);
                    int lightSurface = ColorMath.blend(0xffeee8ee, color, .035f);
                    check(ColorMath.contrast(ColorMath.readable(color, darkSurface, 4.5), darkSurface) >= 4.5,
                            "Readable dark accent text");
                    check(ColorMath.contrast(ColorMath.readable(color, lightSurface, 4.5), lightSurface) >= 4.5,
                            "Readable light accent text");
                }
            }
        }
        System.out.println("AuditAppearanceTest: " + assertions + " checks passed");
    }
}
