package io.ghx.vpn.sub;

import java.util.ArrayList;
import java.util.List;

/**
 * ql 显式包装解码和通用 URI 切分；使用本类拆分并不表示内容属于 ql 格式。
 *
 * <p>实际内容常常长这样：链接之间只隔一个空格、逗号、分号、竖线，甚至首尾直接相连，
 * 例如 {@code vmess://…trojan://…ss://…}。本类只负责把文本切成一条条链接，
 * 再交给 {@link V2rayUriParser} 既有的协议解析器，不新增协议、不联网，
 * 也不改变单条链接原本的解析结果。</p>
 */
public final class QlParser {

    /** 支持的链接前缀，最长优先（ssr:// 必须先于 ss:// 判断）。 */
    private static final String[] SCHEMES = {
            "vmess://", "vless://", "trojan://", "hysteria2://", "hy2://",
            "tuic://", "socks5://", "socks://", "ssr://", "ss://"
    };

    /** 可选的 ql 自描述前缀，正文可以是明文混合，也可以再包一层 base64。 */
    private static final String WRAP = "ql://";
    private static final String WRAP_BARE = "ql:";

    private QlParser() {
    }

    /** 是否带 ql:// 或 ql: 前缀。 */
    public static boolean hasWrapper(String text) {
        String t = text == null ? "" : text.trim();
        return startsWith(t, WRAP) || startsWith(t, WRAP_BARE);
    }

    /** 去掉 ql 前缀；正文若是整段 base64 再解一层；其它情况原样返回。 */
    public static String unwrap(String text) {
        String t = text == null ? "" : text.trim();
        if (!hasWrapper(t)) return t;
        t = startsWith(t, WRAP) ? t.substring(WRAP.length()).trim()
                : t.substring(WRAP_BARE.length()).trim();
        if (!split(t).isEmpty()) return t;
        if (B64.looksLike(t)) {
            String decoded = B64.decodeUtf8(t).trim();
            if (!split(decoded).isEmpty()) return decoded;
        }
        return t;
    }

    /** Explicit, lossless format conversion: never serializes or edits individual URIs. */
    public static String wrap(String raw) {
        String text = SubParser.unwrapBase64(raw);
        int format = SubParser.detect(text);
        if (format != SubParser.FMT_URI && format != SubParser.FMT_QL)
            throw new IllegalArgumentException("只能转换有有效节点的 URI / ql 订阅");
        List<Node> before = SubParser.parseNodes(text, format);
        if (before.isEmpty()) throw new IllegalArgumentException("订阅没有可解析的节点");
        String wrapped = format == SubParser.FMT_QL ? text : WRAP + "\n" + text;
        List<Node> after = parse(wrapped);
        if (before.size() != after.size()) throw new IllegalArgumentException("转换会改变节点，已取消");
        for (int i = 0; i < before.size(); i++)
            if (!before.get(i).toYaml().equals(after.get(i).toYaml()))
                throw new IllegalArgumentException("转换会改变节点参数，已取消");
        return wrapped;
    }

    /** 切成若干条独立链接：任意分隔符都行，直接相连也能切开。 */
    public static List<String> split(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String t = expandEscapes(text);
        int n = t.length();
        int pos = 0;
        while (pos < n) {
            int start = -1;
            String scheme = null;
            for (int i = pos; i < n; i++) {
                String s = schemeAt(t, i);
                if (s != null) {
                    start = i;
                    scheme = s;
                    break;
                }
            }
            if (start < 0) break;
            int end = n;
            for (int i = start + scheme.length(); i < n; i++) {
                if (schemeAt(t, i) != null) {
                    end = i;
                    break;
                }
            }
            String token = trimSeparators(t.substring(start, end));
            if (token.length() > scheme.length() + 2) out.add(token);
            pos = end;
        }
        return out;
    }

    /** 这些链接里真正能解析成节点的条数（识别用，不做任何持久化）。 */
    public static int count(List<String> links) {
        if (links == null) return 0;
        int ok = 0;
        for (int i = 0; i < links.size(); i++) {
            if (parseOne(links.get(i)) != null) ok++;
        }
        return ok;
    }

    /** 解析全部节点；单条失败不影响其它（与单行 URI 列表行为一致）。 */
    public static List<Node> parse(String text) {
        List<Node> out = new ArrayList<Node>();
        List<String> links = split(unwrap(text));
        for (int i = 0; i < links.size(); i++) {
            Node n = parseOne(links.get(i));
            if (n != null) out.add(n);
        }
        return out;
    }

    private static Node parseOne(String link) {
        try {
            return V2rayUriParser.parseOne(link);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** JSON 里常见的转义分隔符先还原成真正的空白，便于统一切分。 */
    private static String expandEscapes(String text) {
        if (text.indexOf('\\') < 0) return text;
        return text.replace("\\r\\n", "\n").replace("\\n", "\n")
                .replace("\\r", "\n").replace("\\t", " ");
    }

    private static String schemeAt(String t, int i) {
        for (int k = 0; k < SCHEMES.length; k++) {
            String s = SCHEMES[k];
            if (i + s.length() <= t.length() && t.regionMatches(true, i, s, 0, s.length())) {
                return s;
            }
        }
        return null;
    }

    private static String trimSeparators(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && isSeparator(s.charAt(b))) b++;
        int squareBalance = 0;
        for (int i = b; i < e; i++) {
            if (s.charAt(i) == '[') squareBalance++;
            else if (s.charAt(i) == ']') squareBalance--;
        }
        while (e > b && isSeparator(s.charAt(e - 1))) {
            char last = s.charAt(e - 1);
            // Keep a matched ']' (notably an IPv6 authority without an explicit port).
            // Only unmatched surrounding brackets belong to the presentation wrapper.
            if (last == ']' && squareBalance >= 0) break;
            if (last == ']') squareBalance++;
            else if (last == '[') squareBalance--;
            e--;
        }
        return s.substring(b, e);
    }

    /** 分隔符只在首尾丢弃：= - _ / : ? & # % 不在其中；匹配的 ] 另行保留。 */
    private static boolean isSeparator(char c) {
        if (c <= ' ') return true;
        switch (c) {
            case ',': case ';': case '|': case '\\': case '"': case '\'':
            case '`': case '(': case ')': case '[': case ']': case '{':
            case '}': case '<': case '>': case '*':
            case '\u3001': case '\u3002': case '\uFF0C': case '\uFF1B':
                return true;
            default:
                return false;
        }
    }

    private static boolean startsWith(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
