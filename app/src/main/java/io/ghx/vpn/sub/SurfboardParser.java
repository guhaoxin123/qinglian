package io.ghx.vpn.sub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Surfboard / Surge 风格的 INI 配置：
 *   [Proxy]
 *   name = ss, host, port, encrypt-method=aes-128-gcm, password=xxx
 *   name = vmess, host, port, username=uuid, ws=true, ws-path=/x, ws-headers=Host:h
 *   name = trojan, host, port, password=p, sni=s
 */
public final class SurfboardParser {

    private SurfboardParser() {
    }

    public static boolean looksLike(String text) {
        String t = text == null ? "" : text;
        return t.indexOf("[Proxy]") >= 0 || t.indexOf("[proxy]") >= 0
                || t.indexOf("[Proxy Group]") >= 0 || t.indexOf("[Proxy Group]".toLowerCase()) >= 0;
    }

    public static List<Node> parse(String text) {
        List<Node> out = new ArrayList<Node>();
        String[] lines = text.split("\\r?\\n");
        boolean inProxy = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) continue;
            if (line.startsWith("[")) {
                inProxy = line.equalsIgnoreCase("[Proxy]");
                continue;
            }
            if (!inProxy) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String name = line.substring(0, eq).trim();
            String body = line.substring(eq + 1).trim();
            Node n = parseOne(name, body);
            if (n != null) out.add(n);
        }
        return out;
    }

    private static Node parseOne(String name, String body) {
        String[] parts = splitCsv(body);
        if (parts.length < 3) return null;
        String type = parts[0].trim().toLowerCase();
        String host = parts[1].trim();
        int port;
        try {
            port = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (host.length() == 0) return null;

        Map<String, String> kv = new LinkedHashMap<String, String>();
        for (int i = 3; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0) continue;
            int eq = p.indexOf('=');
            if (eq < 0) kv.put(p.toLowerCase(), "");
            else kv.put(p.substring(0, eq).trim().toLowerCase(), p.substring(eq + 1).trim());
        }

        if (type.equals("ss") || type.equals("shadowsocks")) {
            Node n = new Node("ss", name, host, port);
            String m = kv.get("encrypt-method");
            if (m == null) m = kv.get("method");
            if (m == null) m = kv.get("cipher");
            n.put("cipher", m);
            n.put("password", kv.get("password"));
            n.put("udp", true);
            applyObfs(n, kv);
            return n;
        }

        if (type.equals("vmess")) {
            Node n = new Node("vmess", name, host, port);
            String u = kv.get("username");
            if (u == null) u = kv.get("uuid");
            n.put("uuid", u);
            String aid = kv.get("alterid");
            if (aid == null) aid = kv.get("aid");
            n.put("alterId", Integer.valueOf(parseInt(aid, 0)));
            n.put("cipher", kv.containsKey("encrypt-method") ? kv.get("encrypt-method") : "auto");
            n.put("udp", true);
            boolean ws = truthy(kv.get("ws"));
            boolean tls = truthy(kv.get("tls"));
            boolean tls13 = truthy(kv.get("tls13"));
            if (ws) {
                n.put("network", "ws");
                String path = kv.get("ws-path");
                if (path == null) path = kv.get("path");
                n.put("ws-opts", Node.wsOpts(path != null ? path : "/", wsHost(kv)));
            }
            if (tls || tls13) {
                n.put("tls", true);
                String sni = kv.containsKey("sni") ? kv.get("sni") : wsHost(kv);
                if (sni != null && sni.length() > 0) {
                    n.put("servername", sni);
                    n.put("sni", sni);
                }
            }
            return n;
        }

        if (type.equals("trojan")) {
            Node n = new Node("trojan", name, host, port);
            String pwd = kv.containsKey("password") ? kv.get("password") : kv.get("username");
            n.put("password", pwd);
            String sni = kv.containsKey("sni") ? kv.get("sni") : wsHost(kv);
            if (sni != null && sni.length() > 0) n.put("sni", sni);
            n.put("skip-cert-verify", truthy(kv.get("skip-cert-verify")));
            if (truthy(kv.get("ws"))) {
                n.put("network", "ws");
                String path = kv.get("ws-path");
                n.put("ws-opts", Node.wsOpts(path != null ? path : "/", wsHost(kv)));
            }
            n.put("udp", true);
            return n;
        }

        if (type.equals("vless")) {
            Node n = new Node("vless", name, host, port);
            String u = kv.get("username");
            if (u == null) u = kv.get("uuid");
            n.put("uuid", u);
            n.put("udp", true);
            if (truthy(kv.get("tls"))) {
                n.put("tls", true);
                String sni = kv.containsKey("sni") ? kv.get("sni") : wsHost(kv);
                if (sni != null && sni.length() > 0) n.put("servername", sni);
            }
            if (truthy(kv.get("ws"))) {
                n.put("network", "ws");
                String path = kv.get("ws-path");
                n.put("ws-opts", Node.wsOpts(path != null ? path : "/", wsHost(kv)));
            }
            String flow = kv.get("flow");
            if (flow != null && flow.length() > 0) n.put("flow", flow);
            return n;
        }

        if (type.equals("hysteria2") || type.equals("hy2")) {
            Node n = new Node("hysteria2", name, host, port);
            n.put("password", kv.containsKey("password") ? kv.get("password") : kv.get("auth"));
            String sni = kv.get("sni");
            if (sni != null) n.put("sni", sni);
            n.put("skip-cert-verify", truthy(kv.get("skip-cert-verify")));
            n.put("udp", true);
            return n;
        }

        return null;
    }

    private static void applyObfs(Node n, Map<String, String> kv) {
        String obfs = kv.get("obfs");
        if (obfs == null || obfs.length() == 0 || obfs.equals("none")) return;
        Map<String, Object> opt = new LinkedHashMap<String, Object>();
        if (obfs.equalsIgnoreCase("http") || obfs.equalsIgnoreCase("tls")) {
            n.put("plugin", "obfs");
            opt.put("mode", obfs.toLowerCase());
            String oh = kv.get("obfs-host");
            if (oh != null) opt.put("host", oh);
            n.put("plugin-opts", opt);
        }
    }

    /** ws-headers=Host:xxx 或 ws-host=xxx */
    private static String wsHost(Map<String, String> kv) {
        String h = kv.get("ws-host");
        if (h != null) return h;
        String hs = kv.get("ws-headers");
        if (hs != null) {
            String[] seg = hs.split("[;,]");
            for (int i = 0; i < seg.length; i++) {
                int c = seg[i].indexOf(':');
                if (c > 0 && seg[i].substring(0, c).trim().equalsIgnoreCase("host")) {
                    return seg[i].substring(c + 1).trim();
                }
            }
        }
        return null;
    }

    /** 按逗号切分，忽略括号里的逗号（简单实现）。 */
    private static String[] splitCsv(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean truthy(String v) {
        if (v == null) return false;
        String t = v.trim();
        return t.equals("1") || t.equalsIgnoreCase("true") || t.equalsIgnoreCase("yes");
    }
}
