package io.ghx.vpn.sub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个代理节点。渲染成 Clash/mihomo 的 flow 风格 YAML。 */
public final class Node {

    public String type;
    public String name;
    public String server;
    public int port;

    /** 协议附加字段，按顺序渲染（值为 String 或 Map 或 List）。 */
    public final LinkedHashMap<String, Object> opts = new LinkedHashMap<String, Object>();

    public Node(String type, String name, String server, int port) {
        this.type = type;
        this.name = name;
        this.server = server;
        this.port = port;
    }

    public Node put(String k, Object v) {
        if (v == null) return this;
        if (v instanceof String && ((String) v).length() == 0) return this;
        opts.put(k, v);
        return this;
    }

    public Node put(String k, boolean v) {
        opts.put(k, Boolean.valueOf(v));
        return this;
    }

    public String key() {
        return type + "://" + server + ":" + port + "#" + name;
    }

    @Override
    public String toString() {
        return name + " [" + type + "] " + server + ":" + port;
    }

    // ---------- YAML 渲染 ----------

    /** 渲染成 `- {name: "x", type: ss, ...}` 这样一行。 */
    public String toYaml() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("- {");
        kv(sb, "name", name).append(", ");
        kv(sb, "type", type).append(", ");
        kv(sb, "server", server).append(", ");
        sb.append("port: ").append(port);
        for (Map.Entry<String, Object> e : opts.entrySet()) {
            sb.append(", ");
            kv(sb, e.getKey(), e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static StringBuilder kv(StringBuilder sb, String k, Object v) {
        sb.append(k).append(": ");
        if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                kv(sb, e.getKey(), e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                Object o = l.get(i);
                if (o instanceof String) quote(sb, (String) o);
                else sb.append(o);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(v));
        }
        return sb;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append('"');
    }

    /** ws-opts 便捷构造。 */
    public static Map<String, Object> wsOpts(String path, String host) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (path != null && path.length() > 0) m.put("path", path);
        if (host != null && host.length() > 0) {
            Map<String, Object> h = new LinkedHashMap<String, Object>();
            h.put("Host", host);
            m.put("headers", h);
        }
        return m;
    }

    public static List<String> alpnList(String alpn) {
        List<String> l = new ArrayList<String>();
        if (alpn == null) return l;
        String[] parts = alpn.split(",");
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.length() > 0) l.add(t);
        }
        return l;
    }
}
