import io.ghx.vpn.core.ConfigBuilder;
import io.ghx.vpn.core.RuleFilter;
import io.ghx.vpn.sub.ClashYaml;
import io.ghx.vpn.sub.Node;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plain-Java regression tests; synthetic inputs only, no Android or network. */
public final class AuditConfigTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static String build(String base, String mode) {
        return new ConfigBuilder().base(base).mode(mode).build();
    }
    private static ClashYaml.Group group(String yaml, String name) {
        for (ClashYaml.Group g : ClashYaml.groups(yaml)) if (name.equals(g.name)) return g;
        throw new AssertionError("Missing group " + name);
    }
    private static int count(String s, String needle) {
        int n = 0, start = 0;
        while ((start = s.indexOf(needle, start)) >= 0) { n++; start += needle.length(); }
        return n;
    }
    private static void rejects(String base) {
        boolean rejected = false;
        try { build(base, ConfigBuilder.MODE_GLOBAL); }
        catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "Invalid duplicate/reserved names must fail explicitly");
    }
    private static void uriRegression() {
        String encoded = io.ghx.vpn.sub.B64.encodeUtf8("aes-128-gcm:p+%2B:@");
        Node n = io.ghx.vpn.sub.V2rayUriParser.parseOne("ss://" + encoded + "@example.invalid:443/?plugin=obfs-local%3Bobfs%3Dhttp#A+B");
        check(n != null && n.port == 443 && "example.invalid".equals(n.server), "SIP002 trailing slash preserves explicit port");
        check("p+%2B:@".equals(n.opts.get("password")), "Base64 payload is not URI-decoded twice");
        check("A+B".equals(n.name) && "obfs".equals(n.opts.get("plugin")), "Literal plus fragment and SIP002 plugin retained");
        n = io.ghx.vpn.sub.V2rayUriParser.parseOne("ss://aes-128-gcm:p%40ss+%2B%3A%25@example.invalid:9443/#plain");
        check(n != null && n.port == 9443 && "p@ss++:%".equals(n.opts.get("password")), "Plain SS userinfo percent-decoded once, plus preserved");
        n = io.ghx.vpn.sub.V2rayUriParser.parseOne("ss://" + encoded + "@[2001:db8::1]:1443/?plugin=obfs-local%3Bobfs%3Dhttp#ipv6");
        check(n != null && n.port == 1443 && "2001:db8::1".equals(n.server), "IPv6 SIP002 optional slash");
        n = io.ghx.vpn.sub.V2rayUriParser.parseOne("ss://" + io.ghx.vpn.sub.B64.encodeUtf8("aes-128-gcm:p+%2B@example.invalid:2443") + "#legacy");
        check(n != null && n.port == 2443 && "p+%2B".equals(n.opts.get("password")), "Legacy base64 SS credentials retained");
        n = io.ghx.vpn.sub.V2rayUriParser.parseOne("trojan://p+%2B%40@example.invalid:443?sni=example.invalid#name+plus");
        check(n != null && "p++@".equals(n.opts.get("password")) && "name+plus".equals(n.name), "Other URI passwords and fragments preserve literal plus");
        n = io.ghx.vpn.sub.V2rayUriParser.parseOne("ss://" + encoded.replace("=", "%3D") + "@example.invalid:443#encoded");
        check(n != null && "p+%2B:@".equals(n.opts.get("password")), "Percent-encoded base64 userinfo decoded at URI layer");
    }

    public static void main(String[] args) {
        String proxies = "proxies:\n"
                + "  - {name: 'inline, node', type: ss, server: example.invalid, port: 443, cipher: aes-128-gcm, password: 'secret: one', plugin-opts: {name: nested}}\n"
                + "  - type: vless\n    name: 'block''s node' # comment\n    server: example.invalid\n    port: 443\n"
                + "    uuid: synthetic-uuid\n    reality-opts:\n      public-key: synthetic-key\n"
                + "    ws-opts:\n      headers:\n        name: not-a-proxy\n";
        List<String> names = ClashYaml.names(proxies, "proxies");
        check(names.equals(Arrays.asList("inline, node", "block's node")), "Inline/block names ignore nested name");
        String[] sections = {"", "proxy-groups: [] # empty\n", "proxy-groups:\n",
                "proxy-groups:\n- name: Original\n  type: select\n  proxies:\n    - 'inline, node'\n",
                "proxy-groups: [{name: Original, type: select, proxies: ['inline, node']}]\n"};
        for (String section : sections) {
            String raw = proxies + section + "rules:\n  - DOMAIN,example.invalid,DIRECT\n  - 'FINAL,Original' # end\n";
            String out = build(raw, ConfigBuilder.MODE_GLOBAL);
            check(out.contains(proxies), "Protocol fields survive byte-for-byte");
            check(count(out, "proxy-groups:") == 1, "One valid groups section");
            ClashYaml.Group select = group(out, ConfigBuilder.selectionGroupName(raw));
            check("select".equals(select.type), "UI group is selector");
            check(select.proxies.containsAll(names), "Selector exposes real nodes");
            check(select.proxies.contains(ConfigBuilder.autoGroupName(raw)), "Selector offers auto");
            check(group(out, ConfigBuilder.autoGroupName(raw)).proxies.equals(names), "Auto contains real nodes only");
            check(out.contains("MATCH," + select.name), "Global targets UI selector");
            check(!out.contains("DOMAIN,example.invalid"), "Global replaces source rules");
            if (section.contains("Original")) {
                check("select".equals(group(out, "Original").type), "Original group preserved");
                check(group(out, "Original").proxies.contains("inline, node"), "Block/flow group members parsed");
            }
            String direct = build(raw, ConfigBuilder.MODE_DIRECT);
            check(direct.contains("MATCH,DIRECT") && !direct.contains("FINAL,Original"), "Direct uses DIRECT only");
            String rule = build(raw, ConfigBuilder.MODE_RULE);
            check(rule.contains("DOMAIN,example.invalid,DIRECT"), "Rule preserves ordinary rules");
            check(rule.contains("MATCH," + select.name) && !rule.contains("FINAL,Original"), "Quoted FINAL remapped");
        }
        String collisions = proxies + "proxy-groups:\n"
                + "  - {name: '节点选择', type: select, proxies: ['inline, node']}\n"
                + "  - {name: '节点选择 (轻连 1)', type: select, proxies: ['inline, node']}\n"
                + "  - {name: '自动选择', type: select, proxies: ['inline, node']}\n"
                + "rules:\n  - DOMAIN,example.invalid,节点选择\n  - MATCH,节点选择\n";
        String selector = ConfigBuilder.selectionGroupName(collisions);
        String automatic = ConfigBuilder.autoGroupName(collisions);
        check(selector.equals("节点选择 (轻连 2)"), "Selector collision suffix skips occupied names");
        check(automatic.equals("自动选择 (轻连 1)"), "Auto collision resolved");
        check(selector.equals(ConfigBuilder.selectionGroupName(collisions)), "Names deterministic");
        String out = build(collisions, ConfigBuilder.MODE_RULE);
        check(out.contains("DOMAIN,example.invalid,节点选择"), "Original group references retained");
        check(out.contains("MATCH," + selector), "Fallback collision-safe");
        check(group(out, selector).proxies.contains(automatic), "Resolved group references");
        Set<String> unique = new HashSet<String>();
        for (ClashYaml.Group g : ClashYaml.groups(out)) check(unique.add(g.name), "No injected duplicates");
        String proxyCollision = "proxies: [{name: '节点选择', type: ss, server: example.invalid, port: 1}]\n";
        check(!ConfigBuilder.GROUP_NAME.equals(ConfigBuilder.selectionGroupName(proxyCollision)), "Proxy names also reserve labels");
        check(group(build(proxyCollision, ConfigBuilder.MODE_GLOBAL), ConfigBuilder.selectionGroupName(proxyCollision)).proxies.contains("节点选择"), "Reserved-label node remains selectable");
        rejects("proxies:\n  - {name: same, type: ss}\n  - {name: same, type: ss}\n");
        rejects(proxies + "proxy-groups:\n  - {name: G, type: select}\n  - {name: G, type: select}\n");
        rejects("proxies:\n  - {name: DIRECT, type: ss}\n");
        rejects("proxies:\n  - {name: same, type: ss}\nproxy-groups:\n  - {name: same, type: select}\n");
        String ruleBase = proxies + "rules:\n  - 'DOMAIN,keep.invalid,DIRECT'\n# root comment is not a new section\n"
                + "  - \"GEOIP,CN,DIRECT\"\n  - 'GEOSITE,cn,DIRECT'\n  - 'RULE-SET,remote,DIRECT'\n"
                + "rule-providers:\n  remote:\n    type: http\n# still inside providers\n    url: https://example.invalid/rules\n"
                + "other-key: keep\n";
        out = build(ruleBase, ConfigBuilder.MODE_RULE);
        check(out.contains("'DOMAIN,keep.invalid,DIRECT'"), "Quoted ordinary rule preserved");
        check(!out.contains("GEOIP,CN") && !out.contains("GEOSITE,cn") && !out.contains("RULE-SET,remote"), "Existing filter handles quoted types");
        check(!out.contains("https://example.invalid/rules") && out.contains("other-key: keep"), "Provider section comments handled");
        check(count(out, "MATCH," + ConfigBuilder.GROUP_NAME) == 1, "Missing fallback added once");
        check(build(proxies, ConfigBuilder.MODE_RULE).contains("MATCH," + ConfigBuilder.GROUP_NAME), "Absent rules get fallback");
        check(build(proxies + "rules: []\n", ConfigBuilder.MODE_RULE).contains("MATCH," + ConfigBuilder.GROUP_NAME), "Empty flow rules get fallback");
        out = build(proxies + "rules: ['DOMAIN,keep.invalid,DIRECT', \"MATCH,DIRECT\"]\n", ConfigBuilder.MODE_RULE);
        check(out.contains("'DOMAIN,keep.invalid,DIRECT'") && out.contains("MATCH," + ConfigBuilder.GROUP_NAME), "Flow rules retained/remapped");
        check(count(RuleFilter.apply("rules:\n- 'MATCH,DIRECT'\n"), "MATCH,") == 1, "Quoted MATCH detected by filter");
        check(RuleFilter.apply("rules:\n- DOMAIN,keep.invalid,DIRECT\n").contains("  - DOMAIN,keep.invalid,DIRECT\n\n  - MATCH,"), "Filter normalizes indentless rules before fallback");
        out = build(proxies + "external-controller: 0.0.0.0:9090\n"
                + "'external-controller-tls': 0.0.0.0:9443\nexternal-controller-unix: /tmp/control\n"
                + "external-controller-pipe: unsafe\nexternal-controller-cors:\n  allow-origins: ['*']\n"
                + "secret: imported\ndns:\n  listen: 0.0.0.0:53\n", ConfigBuilder.MODE_GLOBAL);
        check(out.contains("listen: 127.0.0.1:1053"), "DNS private by default");
        check(out.contains("bind-address: '127.0.0.1'"), "Proxy bind private by default");
        check(count(out, "external-controller:") == 1 && out.contains("external-controller: 127.0.0.1:9090"), "Controller app-owned");
        check(!out.contains("external-controller-tls") && !out.contains("external-controller-unix") && !out.contains("external-controller-pipe") && !out.contains("secret: imported"), "Imported controller variants/auth stripped");
        check(new ConfigBuilder().allowLan(true).build().contains("listen: 0.0.0.0:1053"), "Explicit LAN option honored");
        Node n = new Node("ss", "generated", "example.invalid", 443);
        n.put("password", "synthetic");
        out = new ConfigBuilder().nodes(Arrays.asList(n)).mode(ConfigBuilder.MODE_GLOBAL).build();
        check(out.contains("password: \"synthetic\"") && out.contains("MATCH," + ConfigBuilder.GROUP_NAME), "Generated credentials and selector retained");
        check(!out.contains("IP-CIDR,"), "Generated global routes solely to selector");
        check(new ConfigBuilder().nodes(Arrays.asList(n)).mode(ConfigBuilder.MODE_RULE).build().contains("IP-CIDR,"), "Generated rule retains LAN routing");
        check(new ConfigBuilder().mode(ConfigBuilder.MODE_GLOBAL).build().contains("MATCH,DIRECT"), "Empty generated config direct");
        check(ConfigBuilder.selectionGroupName(null).equals(ConfigBuilder.GROUP_NAME) && ConfigBuilder.autoGroupName(null).equals(ConfigBuilder.GROUP_AUTO), "Null-base API compatible");
        check(ClashYaml.names("proxies:\n  - {name: \"a\\\"b, c\", type: ss}\n  - {name: \"\\u8282\\u70b9\", type: ss}\n", "proxies").equals(Arrays.asList("a\"b, c", "节点")), "Escaped quote/unicode names decoded");
        check(ClashYaml.names("proxies:\n  - {name: Bob's node, type: ss} # comment\n", "proxies").equals(Arrays.asList("Bob's node")), "Plain apostrophe is not quoted scalar");
        check(ClashYaml.names("proxies:\n  - name: first\n    type: ss\n    alpn:\n      - h2\n      - http/1.1\n    ws-opts:\n      headers:\n        name: nested\n  - name: second\n    type: ss\n", "proxies").equals(Arrays.asList("first", "second")), "Nested sequence items do not reset proxy map");
        uriRegression();
        System.out.println("AuditConfigTest: " + checks + " checks passed");
    }
}
