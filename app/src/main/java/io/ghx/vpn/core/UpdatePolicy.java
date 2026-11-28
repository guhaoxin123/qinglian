package io.ghx.vpn.core;

import java.net.URI;
import java.util.Locale;

/** Pure update policy; testable without Android, network or signing secrets. */
public final class UpdatePolicy {
    public static final String REPO = "https://github.com/guhaoxin123/qinglian";
    public static final String HOME = "https://github.com/guhaoxin123";
    public static final String API = "https://api.github.com/repos/guhaoxin123/qinglian/releases/latest";
    public static final long MAX_APK = 100L * 1024 * 1024;
    public static final class Release {
        public final String version, url, digest, notes;
        public final long size;
        public Release(String tag, String asset, String url, String digest, long size, String notes) {
            version = normalize(tag);
            if (!asset.equals("qinglian_" + version + ".apk")) throw new IllegalArgumentException("发行版没有匹配的 APK");
            String expected = REPO + "/releases/download/" + tag + "/" + asset;
            if (!expected.equals(url)) throw new IllegalArgumentException("更新地址不属于指定仓库");
            if (digest == null || !digest.matches("sha256:[a-fA-F0-9]{64}")) throw new IllegalArgumentException("发行版缺少 SHA-256 校验值");
            if (size <= 0 || size > MAX_APK) throw new IllegalArgumentException("安装包大小异常");
            this.url = url; this.digest = digest.substring(7).toLowerCase(Locale.ROOT); this.size = size;
            this.notes = notes == null ? "" : notes.substring(0, Math.min(6000, notes.length()));
        }
    }
    private UpdatePolicy() { }
    public static String normalize(String value) {
        String v = value == null ? "" : value.trim();
        if (v.startsWith("v")) v = v.substring(1);
        if (!v.matches("[0-9]{1,6}(\\.[0-9]{1,6}){1,3}")) throw new IllegalArgumentException("版本号无效");
        return v;
    }
    public static int compare(String a, String b) {
        String[] x = normalize(a).split("\\."); String[] y = normalize(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int l = i < x.length ? Integer.parseInt(x[i]) : 0, r = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (l != r) return l < r ? -1 : 1;
        }
        return 0;
    }
    public static boolean downloadHost(String url) {
        try {
            URI u = new URI(url); String h = u.getHost();
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                    && ("github.com".equals(h) || "release-assets.githubusercontent.com".equals(h) || "objects.githubusercontent.com".equals(h));
        } catch (Exception e) { return false; }
    }
    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2]; char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { out[i*2] = alphabet[(bytes[i] & 255) >>> 4]; out[i*2+1] = alphabet[bytes[i] & 15]; }
        return new String(out);
    }
}
