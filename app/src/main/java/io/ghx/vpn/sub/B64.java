package io.ghx.vpn.sub;

/** 纯 Java base64（不依赖 android.util，方便在 JDK 上跑测试）。 */
public final class B64 {

    private static final char[] STD =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] REV = new int[128];

    static {
        for (int i = 0; i < REV.length; i++) REV[i] = -1;
        for (int i = 0; i < STD.length; i++) REV[STD[i]] = i;
        REV['-'] = 62; // url-safe
        REV['_'] = 63;
    }

    private B64() {
    }

    /** 解码；自动忽略空白、补位和非法字符，支持 url-safe。 */
    public static byte[] decode(String s) {
        if (s == null) return new byte[0];
        int len = s.length();
        byte[] out = new byte[len * 3 / 4 + 4];
        int buf = 0, bits = 0, n = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 128) continue;
            int v = REV[c];
            if (v < 0) continue; // '=', '\n', 空格等
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        byte[] r = new byte[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    public static String decodeUtf8(String s) {
        return new String(decode(s), java.nio.charset.Charset.forName("UTF-8"));
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(STD[(v >> 18) & 63]).append(STD[(v >> 12) & 63])
              .append(STD[(v >> 6) & 63]).append(STD[v & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int v = (data[i] & 0xFF) << 16;
            sb.append(STD[(v >> 18) & 63]).append(STD[(v >> 12) & 63]).append("==");
        } else if (rest == 2) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(STD[(v >> 18) & 63]).append(STD[(v >> 12) & 63]).append(STD[(v >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    public static String encodeUtf8(String s) {
        return encode(s.getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    /** 看起来像 base64？用于区分 base64 订阅和明文订阅。 */
    public static boolean looksLike(String s) {
        if (s == null) return false;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c < 128 && REV[c] >= 0) { n++; continue; }
            if (c == '=') continue;
            return false;
        }
        return n > 16;
    }
}
