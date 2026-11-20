package io.ghx.vpn.sub;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2ray 系订阅：vmess:// vless:// trojan:// ss:// hysteria2:// */
public final class V2rayUriParser {

    private V2rayUriParser() {
    }

    public static boolean looksLike(String line) {
        String t = line.trim();
        return t.startsWith("vmess://") || t.startsWith("vless://")
                || t.startsWith("trojan://") || t.startsWith("ss://")
                || t.startsWith("ssr://") || t.startsWith("hysteria2://")
                || t.startsWith("hy2://") || t.startsWith("tuic://")
                || t.startsWith("socks5://") || t.startsWith("socks://");
    }

    public static List<Node> parse(String text) {
        List<Node> out = new ArrayList<Node>();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#") || line.startsWith("//")) continue;
            try {
                Node n = parseOne(line);
                if (n != null) out.add(n);
            } catch (RuntimeException ignored) {
                // 单个节点解析失败不影响整体
            }
        }
        return out;
    }

    public static Node parseOne(String uri) {
        String t = uri.trim();
        if (t.startsWith("vmess://")) return vmess(t.substring(8));
        if (t.startsWith("vless://")) return vless(t.substring(8));
        if (t.startsWith("trojan://")) return trojan(t.substring(9));
        if (t.startsWith("ss://")) return shadowsocks(t.substring(5));
        if (t.startsWith("hysteria2://")) return hysteria2(t.substring(12));
        if (t.startsWith("hy2://")) return hysteria2(t.substring(6));
        if (t.startsWith("tuic://")) return tuic(t.substring(8));
        if (t.startsWith("socks5://")) return socks(t.substring(9), "socks5");
        if (t.startsWith("socks://")) return socks(t.substring(8), "socks5");
        if (t.startsWith("http://") && t.indexOf('@') > 0) return socks(t.substring(7), "http");
        if (t.startsWith("https://") && t.indexOf('@') > 0) return socks(t.substring(8), "http");
        return null;
    }

    // ---------- 工具 ----------

    private static String dec(String s) {
        if (s == null) return null;
        try {
            // URI components are not application/x-www-form-urlencoded: '+' is literal.
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** 拆出 fragment 作为名字。 */
    private static String takeName(String[] holder, String def) {
        String frag = holder[0];
        int hash = frag.indexOf('#');
        if (hash >= 0) {
            String nm = dec(frag.substring(hash + 1));
            holder[0] = frag.substring(0, hash);
            if (nm != null && nm.length() > 0) return nm;
        }
        return def;
    }

    private static Map<String, String> query(String s) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (s == null) return m;
        int q = s.indexOf('?');
        if (q < 0) return m;
        String qs = s.substring(q + 1);
        String[] parts = qs.split("&");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            int eq = parts[i].indexOf('=');
            if (eq < 0) m.put(parts[i], "");
            else m.put(parts[i].substring(0, eq), dec(parts[i].substring(eq + 1)));
        }
        return m;
    }

    private static String stripQuery(String s) {
        int q = s.indexOf('?');
        return q < 0 ? s : s.substring(0, q);
    }

    private static String[] splitHostPort(String s, int defPort) {
        String hp = stripQuery(s);
        String host = hp;
        int port = defPort;
        if (hp.startsWith("[")) { // ipv6
            int close = hp.indexOf(']');
            if (close > 0) {
                host = hp.substring(1, close);
                if (close + 2 < hp.length() && hp.charAt(close + 1) == ':') {
                    port = toInt(hp.substring(close + 2), defPort);
                }
            }
        } else {
            int colon = hp.lastIndexOf(':');
            if (colon > 0) {
                host = hp.substring(0, colon);
                port = toInt(hp.substring(colon + 1), defPort);
            }
        }
        return new String[]{host, String.valueOf(port)};
    }

    private static int toInt(String s, int def) {
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

    /** 公共的传输层参数（ws/grpc/h2/tcp + tls）。 */
    private static void applyTransport(Node n, Map<String, String> q, String netDefault) {
        String net = q.get("type");
        if (net == null) net = netDefault;
        if (net == null) net = "tcp";
        boolean tls = truthy(q.get("security")) || truthy(q.get("tls")) || "tls".equals(q.get("security"))
                || "reality".equals(q.get("security"));
        String host = q.get("host");
        String path = q.get("path");
        String sni = q.get("sni");
        if (sni == null) sni = q.get("peer");
        if (sni == null) sni = host;

        if ("ws".equals(net)) {
            n.put("network", "ws");
            Map<String, Object> w = Node.wsOpts(path != null ? path : (q.get("wspath") != null ? q.get("wspath") : "/"), host);
            if (!w.isEmpty()) n.put("ws-opts", w);
        } else if ("grpc".equals(net)) {
            n.put("network", "grpc");
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            String svc = q.get("serviceName");
            if (svc == null) svc = q.get("path");
            if (svc != null) g.put("grpc-service-name", svc);
            if (!g.isEmpty()) n.put("grpc-opts", g);
        } else if ("h2".equals(net) || "http".equals(net)) {
            n.put("network", "h2");
            Map<String, Object> h = new LinkedHashMap<String, Object>();
            if (path != null) h.put("path", path);
            if (host != null) {
                List<String> hs = new ArrayList<String>();
                hs.add(host);
                h.put("host", hs);
            }
            if (!h.isEmpty()) n.put("h2-opts", h);
        }

        if (tls) {
            n.put("tls", true);
            if (sni != null && sni.length() > 0) n.put("servername", sni);
            String alpn = q.get("alpn");
            if (alpn != null) {
                List<String> list = Node.alpnList(alpn);
                if (!list.isEmpty()) n.put("alpn", list);
            }
            n.put("skip-cert-verify", truthy(q.get("allowInsecure")) || truthy(q.get("insecure")));
            String fp = q.get("fp");
            if (fp != null && fp.length() > 0) n.put("client-fingerprint", fp);

            String pk = q.get("pbk");
            if (pk == null) pk = q.get("public-key");
            if (pk != null && pk.length() > 0) {
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("public-key", pk);
                String sid = q.get("sid");
                if (sid != null && sid.length() > 0) r.put("short-id", sid);
                n.put("reality-opts", r);
            }
        }
    }

    // ---------- 各协议 ----------

    /** vmess://base64(json) */
    private static Node vmess(String body) {
        String json = B64.decodeUtf8(body);
        if (json.indexOf('{') < 0) { // 少数客户端是明文 uri 形式
            return vmessUri(body);
        }
        Map<String, Object> m = Json.parseObject(json);
        String server = Json.str(m, "add");
        int port = Json.i(m, "port", 443);
        String name = Json.str(m, "ps", server + ":" + port);
        if (server == null || server.length() == 0) return null;

        Node n = new Node("vmess", name, server, port);
        n.put("uuid", Json.str(m, "id"));
        n.put("alterId", Integer.valueOf(Json.i(m, "aid", 0)));
        n.put("cipher", Json.str(m, "scy", "auto"));
        n.put("udp", true);

        Map<String, String> q = new LinkedHashMap<String, String>();
        q.put("type", Json.str(m, "net", "tcp"));
        q.put("host", Json.str(m, "host"));
        q.put("path", Json.str(m, "path"));
        q.put("sni", Json.str(m, "sni"));
        String tls = Json.str(m, "tls");
        q.put("security", ("tls".equalsIgnoreCase(tls) || truthy(tls)) ? "tls" : "");
        q.put("fp", Json.str(m, "fp"));
        q.put("alpn", Json.str(m, "alpn"));
        applyTransport(n, q, "tcp");
        return n;
    }

    /** vmess://uuid@host:port?type=ws#name（少见） */
    private static Node vmessUri(String body) {
        String[] h = new String[]{"vmess://" + body};
        String name = takeName(h, "vmess");
        String rest = h[0].substring("vmess://".length());
        int at = rest.indexOf('@');
        if (at < 0) return null;
        String uuid = dec(rest.substring(0, at));
        String[] hp = splitHostPort(rest.substring(at + 1), 443);
        Node n = new Node("vmess", name, hp[0], toInt(hp[1], 443));
        n.put("uuid", uuid);
        n.put("alterId", Integer.valueOf(0));
        n.put("cipher", "auto");
        n.put("udp", true);
        applyTransport(n, query(rest), "tcp");
        return n;
    }

    /** vless://uuid@host:port?type=ws&security=tls&sni=..#name */
    private static Node vless(String body) {
        String[] h = new String[]{"vless://" + body};
        String name = takeName(h, "vless");
        String rest = h[0].substring("vless://".length());
        int at = rest.indexOf('@');
        if (at < 0) return null;
        String uuid = dec(rest.substring(0, at));
        Map<String, String> q = query(rest);
        String[] hp = splitHostPort(rest.substring(at + 1), 443);

        Node n = new Node("vless", name, hp[0], toInt(hp[1], 443));
        n.put("uuid", uuid);
        n.put("udp", true);
        String flow = q.get("flow");
        if (flow != null && flow.length() > 0) n.put("flow", flow);
        applyTransport(n, q, "tcp");
        return n;
    }

    /** trojan://password@host:port?sni=..#name */
    private static Node trojan(String body) {
        String[] h = new String[]{"trojan://" + body};
        String name = takeName(h, "trojan");
        String rest = h[0].substring("trojan://".length());
        int at = rest.indexOf('@');
        if (at < 0) return null;
        String pwd = dec(rest.substring(0, at));
        Map<String, String> q = query(rest);
        String[] hp = splitHostPort(rest.substring(at + 1), 443);

        Node n = new Node("trojan", name, hp[0], toInt(hp[1], 443));
        n.put("password", pwd);
        n.put("udp", true);
        String sni = q.get("sni");
        if (sni == null) sni = q.get("peer");
        if (sni != null && sni.length() > 0) n.put("sni", sni);
        n.put("skip-cert-verify", truthy(q.get("allowInsecure")) || truthy(q.get("insecure")));
        String alpn = q.get("alpn");
        if (alpn != null) {
            List<String> list = Node.alpnList(alpn);
            if (!list.isEmpty()) n.put("alpn", list);
        }
        String net = q.get("type");
        if ("ws".equals(net)) {
            n.put("network", "ws");
            Map<String, Object> w = Node.wsOpts(q.get("path") != null ? q.get("path") : "/", q.get("host"));
            if (!w.isEmpty()) n.put("ws-opts", w);
        } else if ("grpc".equals(net)) {
            n.put("network", "grpc");
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            String svc = q.get("serviceName");
            if (svc != null) g.put("grpc-service-name", svc);
            if (!g.isEmpty()) n.put("grpc-opts", g);
        }
        return n;
    }

    /** ss://base64(method:pass)@host:port#name 或 ss://base64(method:pass@host:port)#name */
    private static Node shadowsocks(String body) {
        String[] h = new String[]{"ss://" + body};
        String name = takeName(h, "ss");
        String rest = h[0].substring("ss://".length());

        String main = stripQuery(rest);
        String method = null, pwd = null, host = null;
        int port = 8388;

        int at = main.lastIndexOf('@');
        if (at > 0) {
            // SIP002 permits percent-encoded plain userinfo and URL-safe base64 userinfo.
            // Decode the URI layer once; base64 payload passwords must stay byte-for-byte.
            String cred = dec(main.substring(0, at));
            String decoded = cred;
            if (cred.indexOf(':') < 0) decoded = B64.decodeUtf8(cred);
            int c = decoded.indexOf(':');
            if (c > 0) {
                method = decoded.substring(0, c);
                pwd = decoded.substring(c + 1);
            }
            String authority = main.substring(at + 1);
            // SIP002's optional '/' separates the authority from plugin query parameters.
            if (authority.endsWith("/")) authority = authority.substring(0, authority.length() - 1);
            String[] hp = splitHostPort(authority, 8388);
            host = hp[0];
            port = toInt(hp[1], 8388);
        } else {
            String decoded = B64.decodeUtf8(main);
            int at2 = decoded.lastIndexOf('@');
            if (at2 < 0) return null;
            String cred = decoded.substring(0, at2);
            int c = cred.indexOf(':');
            if (c > 0) {
                method = cred.substring(0, c);
                pwd = cred.substring(c + 1);
            }
            String[] hp = splitHostPort(decoded.substring(at2 + 1), 8388);
            host = hp[0];
            port = toInt(hp[1], 8388);
        }
        if (host == null || method == null) return null;

        Node n = new Node("ss", name, host, port);
        n.put("cipher", normalizeSsCipher(method));
        n.put("password", pwd);
        n.put("udp", true);
        Map<String, String> q = query(rest);
        String plugin = q.get("plugin");
        if (plugin != null && plugin.length() > 0) {
            // plugin=obfs-local;obfs=http;obfs-host=xxx 或 v2ray-plugin;...
            Map<String, Object> po = new LinkedHashMap<String, Object>();
            String[] seg = plugin.split(";");
            String pname = seg[0];
            if (pname.startsWith("obfs-local") || pname.startsWith("simple-obfs")) {
                po.put("mode", "obfs");
                String obfs = null, obfsHost = null;
                for (int i = 1; i < seg.length; i++) {
                    if (seg[i].startsWith("obfs=")) obfs = seg[i].substring(5);
                    else if (seg[i].startsWith("obfs-host=")) obfsHost = seg[i].substring(10);
                }
                if (obfs != null) po.put("host", obfsHost != null ? obfsHost : "");
                Map<String, Object> opt = new LinkedHashMap<String, Object>();
                if (obfs != null) opt.put("mode", obfs);
                if (obfsHost != null) opt.put("host", obfsHost);
                n.put("plugin", "obfs");
                n.put("plugin-opts", opt);
            } else if (pname.startsWith("v2ray-plugin")) {
                Map<String, Object> opt = new LinkedHashMap<String, Object>();
                opt.put("mode", "websocket");
                for (int i = 1; i < seg.length; i++) {
                    if ("tls".equals(seg[i])) opt.put("tls", true);
                    else if (seg[i].startsWith("host=")) opt.put("host", seg[i].substring(5));
                    else if (seg[i].startsWith("path=")) opt.put("path", seg[i].substring(5));
                }
                n.put("plugin", "v2ray-plugin");
                n.put("plugin-opts", opt);
            }
        }
        return n;
    }

    private static String normalizeSsCipher(String m) {
        if (m == null) return "aes-256-gcm";
        String t = m.trim().toLowerCase();
        if (t.equals("chacha20-ietf-poly1305")) return "chacha20-ietf-poly1305";
        if (t.equals("chacha20-poly1305")) return "chacha20-ietf-poly1305";
        return t;
    }

    /** hysteria2://auth@host:port?sni=...#name */
    private static Node hysteria2(String body) {
        String[] h = new String[]{"hysteria2://" + body};
        String name = takeName(h, "hysteria2");
        String rest = h[0].substring("hysteria2://".length());
        Map<String, String> q = query(rest);
        int at = rest.indexOf('@');
        String auth;
        String hostpart;
        if (at > 0) {
            auth = dec(rest.substring(0, at));
            hostpart = rest.substring(at + 1);
        } else {
            auth = q.get("auth");
            hostpart = rest;
        }
        String[] hp = splitHostPort(hostpart, 443);
        Node n = new Node("hysteria2", name, hp[0], toInt(hp[1], 443));
        if (auth != null && auth.length() > 0) n.put("password", auth);
        String sni = q.get("sni");
        if (sni != null && sni.length() > 0) n.put("sni", sni);
        n.put("skip-cert-verify", truthy(q.get("insecure")) || truthy(q.get("allowInsecure")));
        String obfs = q.get("obfs");
        if (obfs != null && obfs.length() > 0) {
            n.put("obfs", obfs);
            String op = q.get("obfs-password");
            if (op != null) n.put("obfs-password", op);
        }
        String alpn = q.get("alpn");
        if (alpn != null) {
            List<String> list = Node.alpnList(alpn);
            if (!list.isEmpty()) n.put("alpn", list);
        }
        n.put("udp", true);
        return n;
    }

    /** tuic://uuid:password@host:port?... */
    private static Node tuic(String body) {
        String[] h = new String[]{"tuic://" + body};
        String name = takeName(h, "tuic");
        String rest = h[0].substring("tuic://".length());
        int at = rest.lastIndexOf('@');
        if (at < 0) return null;
        String cred = dec(rest.substring(0, at));
        Map<String, String> q = query(rest);
        String[] hp = splitHostPort(rest.substring(at + 1), 443);
        Node n = new Node("tuic", name, hp[0], toInt(hp[1], 443));
        int c = cred.indexOf(':');
        if (c > 0) {
            n.put("uuid", cred.substring(0, c));
            n.put("password", cred.substring(c + 1));
        }
        String sni = q.get("sni");
        if (sni != null) n.put("sni", sni);
        String cc = q.get("congestion_control");
        if (cc != null) n.put("congestion-controller", cc);
        n.put("udp", true);
        return n;
    }

    /** socks5://user:pass@host:port#name → 同时可用于 http 代理。 */
    private static Node socks(String body, String type) {
        String[] h = new String[]{type + "://" + body};
        String name = takeName(h, type);
        String rest = h[0].substring(type.length() + 3);
        int at = rest.lastIndexOf('@');
        if (at < 0) return null;
        String cred = dec(rest.substring(0, at));
        String[] hp = splitHostPort(rest.substring(at + 1), 1080);

        Node n = new Node(type, name, hp[0], toInt(hp[1], 1080));
        int c = cred.indexOf(':');
        if (c > 0) {
            n.put("username", cred.substring(0, c));
            n.put("password", cred.substring(c + 1));
        }
        if ("socks5".equals(type)) {
            n.put("udp", true);
        } else {
            n.put("tls", false);
        }
        return n;
    }
}
