package io.ghx.vpn.sub;

import java.util.List;

/** 订阅格式识别与统一解析入口。 */
public final class SubParser {

    public static final int FMT_UNKNOWN = 0;
    public static final int FMT_CLASH = 1;
    public static final int FMT_URI = 2;
    public static final int FMT_SURFBOARD = 3;
    /** 显式 ql 包装或同一行混排多个协议链接；不同协议本身不等于 ql 格式。 */
    public static final int FMT_QL = 4;

    private SubParser() {
    }

    /** A format badge is deliberately independent of the node protocols. */
    public static String formatLabel(int format, boolean english) {
        switch (format) {
            case FMT_CLASH: return "Clash";
            case FMT_URI: return "v2ray";
            case FMT_SURFBOARD: return "Surfboard";
            case FMT_QL: return english ? "ql mixed" : "ql 混合";
            default: return english ? "Unknown" : "待识别";
        }
    }

    /** 识别格式。content 为已解 base64 的文本。 */
    public static int detect(String content) {
        if (content == null) return FMT_UNKNOWN;
        String t = normalize(content);
        if (t.length() == 0) return FMT_UNKNOWN;
        if (SurfboardParser.looksLike(t)) return FMT_SURFBOARD;
        // Clash: 有 proxies: 顶层键
        if (hasClashKey(t, "proxies") || hasClashKey(t, "proxy-groups")) return FMT_CLASH;
        // ql: 显式前缀优先；正文里的链接可以一条条拆开
        if (QlParser.hasWrapper(t) && !QlParser.split(QlParser.unwrap(t)).isEmpty()) return FMT_QL;
        String[] lines = t.split("\\r?\\n");
        boolean anyLink = false;
        for (int i = 0; i < lines.length; i++) {
            List<String> links = QlParser.split(lines[i]);
            if (links.isEmpty()) continue;
            anyLink = true;
            // 同一行里混排了 2 条以上链接（两个、三个都算）＝ ql 混合格式
            if (links.size() > 1 && QlParser.count(links) > 1) return FMT_QL;
        }
        if (anyLink) return FMT_URI;
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
        if (fmt == FMT_QL) return QlParser.parse(content);
        if (fmt == FMT_URI) return V2rayUriParser.parse(content);
        if (fmt == FMT_SURFBOARD) return SurfboardParser.parse(content);
        return new java.util.ArrayList<Node>();
    }

    /** 订阅可能是整体 base64（少数是套两层），自动解到能识别为止，最多两层。 */
    public static String unwrapBase64(String raw) {
        if (raw == null) return "";
        String original = normalize(raw);
        String t = original;
        for (int round = 0; round < 2; round++) {
            if (detect(t) != FMT_UNKNOWN) return t;
            if (!B64.looksLike(t)) break;
            String dec = normalize(B64.decodeUtf8(t));
            if (dec.length() == 0 || dec.equals(t)) break;
            t = dec;
        }
        return detect(t) != FMT_UNKNOWN ? t : original;
    }

    private static String normalize(String text) {
        String t = text.trim();
        if (t.startsWith("\uFEFF")) t = t.substring(1).trim();
        return t;
    }
}
