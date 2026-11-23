package io.ghx.vpn.net;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * 从 URL 导入的输入规整：只接受 http/https，给一个安全的默认配置名。
 * 纯 Java（无 Android 依赖），离线测试可覆盖。
 */
public final class UrlSource {

    public static final int MAX_URL = 4096;
    private static final int MAX_NAME = 40;

    private UrlSource() {
    }

    /** 返回规整后的链接；不合法（空、非 http/https、含空白或反斜杠、没有主机名）返回 null。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.length() == 0 || t.length() > MAX_URL) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= ' ' || c == '\u007f' || c == '\\') return null;
        }
        String rest;
        if (startsWithIgnoreCase(t, "https://")) rest = t.substring(8);
        else if (startsWithIgnoreCase(t, "http://")) rest = t.substring(7);
        else return null;
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        if (end == 0) return null;
        return t;
    }

    /** 由链接猜一个配置名：优先用路径最后一段（去掉扩展名），没有则用主机名。 */
    public static String guessName(String url) {
        String t = normalize(url);
        if (t == null) return null;
        String rest = t.substring(t.indexOf("://") + 3);
        int cut = rest.length();
        int hash = rest.indexOf('#');
        if (hash >= 0 && hash < cut) cut = hash;
        int query = rest.indexOf('?');
        if (query >= 0 && query < cut) cut = query;
        rest = rest.substring(0, cut);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash + 1);
        int lastSlash = tail.lastIndexOf('/');
        if (lastSlash >= 0) tail = tail.substring(lastSlash + 1);
        int port = host.lastIndexOf(':');
        if (port > 0) host = host.substring(0, port);
        String candidate = tail.length() == 0 ? "" : unescape(tail).trim();
        int dot = candidate.lastIndexOf('.');
        if (dot > 0 && candidate.length() - dot <= 6) candidate = candidate.substring(0, dot).trim();
        if (candidate.length() == 0) candidate = host.trim();
        return sanitize(candidate);
    }

    private static String unescape(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            return text;
        } catch (IllegalArgumentException error) {
            return text;
        }
    }

    /** 去掉控制字符与双向控制符，限制长度；全空时返回 null。 */
    private static String sanitize(String text) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < text.length() && safe.length() < MAX_NAME; i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c) && c != '\u202e' && c != '\u202d') safe.append(c);
        }
        String name = safe.toString().trim();
        return name.length() == 0 ? null : name;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
