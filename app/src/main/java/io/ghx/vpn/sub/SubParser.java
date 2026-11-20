package io.ghx.vpn.sub;

import java.util.List;

/** 订阅格式识别与统一解析入口。 */
public final class SubParser {

    public static final int FMT_UNKNOWN = 0;
    public static final int FMT_CLASH = 1;
    public static final int FMT_URI = 2;
    public static final int FMT_SURFBOARD = 3;

    private SubParser() {
    }

    /** 识别格式。content 为已解 base64 的文本。 */
    public static int detect(String content) {
        if (content == null) return FMT_UNKNOWN;
        String t = normalize(content);
        if (t.length() == 0) return FMT_UNKNOWN;
        if (SurfboardParser.looksLike(t)) return FMT_SURFBOARD;
        // Clash: 有 proxies: 顶层键
        if (hasClashKey(t, "proxies") || hasClashKey(t, "proxy-groups")) return FMT_CLASH;
        String[] lines = t.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (V2rayUriParser.looksLike(lines[i])) return FMT_URI;
        }
        // Detection is plaintext-only. Decoding here would make unwrapBase64 return
        // encoded Clash unchanged after mistaking it for already-decoded YAML.
        return FMT_UNKNOWN;
    }

    private static boolean hasClashKey(String text, String key) {
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith(key + ":")) return true;
        }
        return false;
    }

    /** 解析节点列表（Clash 原文由内核自己解析，这里返回空）。 */
    public static List<Node> parseNodes(String content, int fmt) {
        if (fmt == FMT_URI) return V2rayUriParser.parse(content);
        if (fmt == FMT_SURFBOARD) return SurfboardParser.parse(content);
        return new java.util.ArrayList<Node>();
    }

    /** 订阅可能是整体 base64，自动解一层。 */
    public static String unwrapBase64(String raw) {
        if (raw == null) return "";
        String t = normalize(raw);
        if (detect(t) != FMT_UNKNOWN) return t;
        if (B64.looksLike(t)) {
            String dec = normalize(B64.decodeUtf8(t));
            if (dec.length() > 0 && detect(dec) != FMT_UNKNOWN) return dec;
        }
        return t;
    }

    private static String normalize(String text) {
        String t = text.trim();
        if (t.startsWith("\uFEFF")) t = t.substring(1).trim();
        return t;
    }
}
