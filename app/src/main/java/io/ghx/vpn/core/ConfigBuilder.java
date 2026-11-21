package io.ghx.vpn.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.ghx.vpn.sub.ClashYaml;
import io.ghx.vpn.sub.Node;

/** Builds runtime Clash YAML without reserializing subscription protocol details. */
public final class ConfigBuilder {
    public static final String MODE_DIRECT = "direct";
    public static final String MODE_RULE = "rule";
    public static final String MODE_GLOBAL = "global";
    public static final String GROUP_NAME = "节点选择";
    public static final String GROUP_AUTO = "自动选择";

    private String mode = MODE_DIRECT;
    private int mixedPort = 7890;
    private String controller = "127.0.0.1:9090";
    private final List<Node> nodes = new ArrayList<Node>();
    private String baseConfig;
    private boolean allowLan;
    private int logLevel = 1;
    private String dnsNameserver = "223.5.5.5";

    public ConfigBuilder mode(String m) { mode = m == null ? MODE_DIRECT : m; return this; }
    public ConfigBuilder mixedPort(int p) { mixedPort = p; return this; }
    public ConfigBuilder controller(String c) { controller = c; return this; }
    /** TUN is owned by the app and handed to the core, never created by YAML. */
    public ConfigBuilder tun(boolean b) { return this; }
    public ConfigBuilder allowLan(boolean b) { allowLan = b; return this; }
    public ConfigBuilder logLevel(int l) { logLevel = l; return this; }
    public ConfigBuilder nodes(List<Node> list) {
        nodes.clear();
        if (list != null) nodes.addAll(list);
        return this;
    }
    public ConfigBuilder base(String yaml) { baseConfig = yaml; return this; }

    /** Resolve against RAW subscription, not the already injected runtime YAML. */
    public static String selectionGroupName(String rawBase) { return groupNames(rawBase)[0]; }
    public static String autoGroupName(String rawBase) { return groupNames(rawBase)[1]; }

    private static String[] groupNames(String rawBase) {
        Set<String> occupied = new HashSet<String>();
        occupied.addAll(ClashYaml.names(rawBase, "proxies"));
        occupied.addAll(ClashYaml.names(rawBase, "proxy-groups"));
        String select = unusedName(GROUP_NAME, occupied);
        occupied.add(select);
        return new String[] {select, unusedName(GROUP_AUTO, occupied)};
    }

    private static String unusedName(String preferred, Set<String> occupied) {
        String name = preferred;
        for (int i = 1; occupied.contains(name); i++) name = preferred + " (轻连 " + i + ")";
        return name;
    }

    public String build() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# 轻连 配置（自动生成）\n");
        sb.append("mixed-port: ").append(mixedPort).append('\n');
        sb.append("allow-lan: ").append(allowLan).append('\n');
        sb.append("bind-address: ").append(allowLan ? "'*'" : "'127.0.0.1'").append('\n');
        // Core global mode uses GLOBAL rather than the app selector: use explicit rules instead.
        sb.append("mode: rule\n");
        sb.append("log-level: ").append(logLevelName()).append('\n');
        sb.append("ipv6: false\n");
        sb.append("external-controller: ").append(controller).append('\n');
        sb.append("unified-delay: false\n");
        sb.append("tcp-concurrent: true\n");
        sb.append("find-process-mode: 'off'\n");
        sb.append("keep-alive-interval: 30\n");
        sb.append("keep-alive-idle: 600\n");
        sb.append("connect-timeout: 15000\n");
        sb.append("profile:\n  store-selected: true\n  store-fake-ip: true\n");
        sb.append("tun:\n  enable: false\n");
        sb.append("dns:\n  enable: true\n  ipv6: false\n");
        sb.append("  listen: ").append(allowLan ? "0.0.0.0" : "127.0.0.1").append(":1053\n");
        sb.append("  enhanced-mode: fake-ip\n  fake-ip-range: 198.18.0.1/16\n");
        sb.append("  fake-ip-filter:\n    - '*.lan'\n    - '*.local'\n    - localhost.ptlogin2.qq.com\n");
        sb.append("  default-nameserver:\n    - ").append(dnsNameserver).append('\n');
        sb.append("  nameserver:\n    - ").append(dnsNameserver).append("\n    - 119.29.29.29\n");

        if (baseConfig != null && baseConfig.trim().length() > 0) {
            validateNames(baseConfig);
            appendTail(sb, baseConfig);
            List<String> names = ClashYaml.names(baseConfig, "proxies");
            String[] groups = groupNames(baseConfig);
            String result = injectGroups(sb.toString(), names, groups[0], groups[1]);
            if (MODE_DIRECT.equals(mode)) result = replaceRules(result, "  - MATCH,DIRECT\n");
            else if (MODE_GLOBAL.equals(mode)) result = replaceRules(result, "  - MATCH," + groups[0] + "\n");
            else result = remapMatchTarget(result, groups[0]);
            return RuleFilter.apply(result, groups[0]);
        }

        List<String> names = new ArrayList<String>();
        Set<String> used = new HashSet<String>();
        used.add(GROUP_NAME);
        used.add(GROUP_AUTO);
        for (Node node : nodes) {
            validateName(node.name, used);
            names.add(node.name);
        }
        sb.append(nodes.isEmpty() ? "proxies: []\n" : "proxies:\n");
        for (Node node : nodes) sb.append("  ").append(node.toYaml()).append('\n');
        sb.append("proxy-groups:\n");
        sb.append(appGroups("  ", names, GROUP_NAME, GROUP_AUTO, false));
        sb.append("rules:\n");
        if (MODE_DIRECT.equals(mode) || nodes.isEmpty()) sb.append("  - MATCH,DIRECT\n");
        else {
            if (!MODE_GLOBAL.equals(mode)) {
                sb.append("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n");
                sb.append("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n");
                sb.append("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n");
                sb.append("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n");
                sb.append("  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve\n");
                sb.append("  - IP-CIDR6,fc00::/7,DIRECT,no-resolve\n");
            }
            sb.append("  - MATCH,").append(GROUP_NAME).append('\n');
        }
        return RuleFilter.apply(sb.toString());
    }

    private static void validateNames(String yaml) {
        Set<String> used = new HashSet<String>();
        for (String name : ClashYaml.names(yaml, "proxies")) validateName(name, used);
        for (String name : ClashYaml.names(yaml, "proxy-groups")) validateName(name, used);
    }

    private static void validateName(String name, Set<String> used) {
        if (name == null || name.length() == 0 || name.equals("DIRECT") || name.equals("REJECT")
                || name.equals("GLOBAL") || name.equals("REJECT-DROP") || name.equals("PASS")
                || name.equals("COMPATIBLE") || !used.add(name)) {
            throw new IllegalArgumentException("Duplicate or reserved proxy/group name: " + name);
        }
    }

    /** Append app-owned groups; do not overwrite a similarly named subscription group. */
    private static String injectGroups(String yaml, List<String> names, String select, String auto) {
        StringBuilder out = new StringBuilder(yaml.length() + 512);
        boolean inGroups = false, injected = false;
        String indent = null;
        boolean providers = false;
        for (String line : yaml.split("\\r?\\n", -1)) {
            if ("proxy-providers".equals(ClashYaml.topKey(line))) providers = true;
        }
        for (String line : yaml.split("\\r?\\n", -1)) {
            String key = ClashYaml.topKey(line);
            if (inGroups && key != null) {
                out.append(appGroups(indent == null ? "  " : indent, names, select, auto, providers));
                injected = true;
                inGroups = false;
            }
            if ("proxy-groups".equals(key)) {
                if (injected) throw new IllegalArgumentException("Duplicate proxy-groups section");
                inGroups = true;
                out.append("proxy-groups:\n");
                String tail = ClashYaml.stripComment(line.substring(line.indexOf(':') + 1)).trim();
                if (tail.startsWith("[") && tail.endsWith("]")) {
                    for (String item : ClashYaml.flowList(tail)) out.append("  - ").append(item).append('\n');
                    indent = "  ";
                } else if (tail.length() != 0) {
                    throw new IllegalArgumentException("Unsupported proxy-groups value; expected block or flow list");
                }
                continue;
            }
            if (inGroups && indent == null && line.trim().startsWith("- ")) {
                indent = line.substring(0, line.indexOf('-'));
            }
            out.append(line).append('\n');
        }
        if (!injected) {
            if (!inGroups) out.append("proxy-groups:\n");
            out.append(appGroups(indent == null ? "  " : indent, names, select, auto, providers));
        }
        return out.toString();
    }

    private static String appGroups(String indent, List<String> names, String select, String auto, boolean providers) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("- {name: ").append(quote(select)).append(", type: select, proxies: [")
                .append(quote(auto)).append(", DIRECT");
        for (String name : names) sb.append(", ").append(quote(name));
        sb.append("]");
        if (providers) sb.append(", include-all-providers: true");
        sb.append("}\n");
        sb.append(indent).append("- {name: ").append(quote(auto)).append(", type: url-test, proxies: [");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(names.get(i)));
        }
        if (names.isEmpty() && !providers) sb.append("DIRECT");
        sb.append("], url: 'http://www.gstatic.com/generate_204', interval: 180, tolerance: 80");
        if (providers) sb.append(", include-all-providers: true");
        return sb.append("}\n").toString();
    }

    private static String remapMatchTarget(String yaml, String target) {
        StringBuilder out = new StringBuilder(yaml.length());
        boolean inRules = false, sawRules = false;
        for (String line : yaml.split("\\r?\\n", -1)) {
            String key = ClashYaml.topKey(line);
            if (key != null) {
                inRules = "rules".equals(key);
                if (inRules) {
                    if (sawRules) throw new IllegalArgumentException("Duplicate rules section");
                    sawRules = true;
                    out.append("rules:\n");
                    String tail = ClashYaml.stripComment(line.substring(line.indexOf(':') + 1)).trim();
                    if (tail.startsWith("[")) {
                        for (String rule : ClashYaml.flowList(tail)) appendRule(out, rule, target);
                    } else if (tail.length() != 0) {
                        throw new IllegalArgumentException("Unsupported rules value; expected block or flow list");
                    }
                    continue;
                }
            }
            String t = line.trim();
            if (inRules && t.startsWith("- ")) appendRule(out, t.substring(2), target);
            else out.append(line).append('\n');
        }
        if (!sawRules) out.append("rules:\n  - MATCH,").append(target).append('\n');
        return out.toString();
    }

    private static void appendRule(StringBuilder out, String raw, String target) {
        String rule = ClashYaml.scalar(raw);
        String type = rule.split(",", 2)[0].trim();
        if ("MATCH".equalsIgnoreCase(type) || "FINAL".equalsIgnoreCase(type)) {
            out.append("  - MATCH,").append(target).append('\n');
        } else out.append("  - ").append(raw.trim()).append('\n');
    }

    private static String replaceRules(String yaml, String body) {
        StringBuilder out = new StringBuilder(yaml.length());
        boolean inRules = false, wrote = false;
        for (String line : yaml.split("\\r?\\n", -1)) {
            String key = ClashYaml.topKey(line);
            if (key != null) {
                inRules = "rules".equals(key);
                if (inRules) {
                    if (!wrote) out.append("rules:\n").append(body);
                    wrote = true;
                }
            }
            if (!inRules) out.append(line).append('\n');
        }
        if (!wrote) out.append("rules:\n").append(body);
        return out.toString();
    }

    private void appendTail(StringBuilder sb, String yaml) {
        boolean skipping = false;
        for (String line : yaml.split("\\r?\\n")) {
            String key = ClashYaml.topKey(line);
            if (key != null) skipping = isOverriddenTopKey(key);
            // Document delimiters from the subscription must not split the generated config.
            if (line.trim().equals("---") || line.trim().equals("...")) continue;
            if (!skipping) sb.append(line).append('\n');
        }
    }

    private static boolean isOverriddenTopKey(String k) {
        return k.equals("port") || k.equals("socks-port") || k.equals("mixed-port")
                || k.equals("allow-lan") || k.equals("mode") || k.equals("log-level")
                || k.equals("external-controller") || k.equals("external-controller-tls")
                || k.equals("external-controller-unix") || k.equals("external-controller-pipe")
                || k.equals("external-controller-cors") || k.equals("secret")
                || k.equals("ipv6") || k.equals("bind-address") || k.equals("dns") || k.equals("tun")
                || k.equals("unified-delay") || k.equals("tcp-concurrent")
                || k.equals("find-process-mode") || k.equals("profile")
                || k.equals("keep-alive-interval") || k.equals("keep-alive-idle")
                || k.equals("connect-timeout");
    }

    private String logLevelName() {
        switch (logLevel) {
            case 0: return "silent";
            case 1: return "info";
            case 2: return "warning";
            case 3: return "error";
            default: return "debug";
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
