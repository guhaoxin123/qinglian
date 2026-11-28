import io.ghx.vpn.sub.B64;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.sub.QlParser;
import io.ghx.vpn.sub.SubParser;
import io.ghx.vpn.sub.V2rayUriParser;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Synthetic pure-Java URI regressions; no Android, files, network, JNI or VPN. */
public final class AuditUriEdges {
    private static final String UUID = "11111111-1111-1111-1111-111111111111";
    private static final String IPV6 = "2001:db8::1";
    private static final String[] SCHEMES = {"vless", "trojan", "hysteria2", "hy2", "tuic", "socks5", "socks", "ss"};
    private static final String OPTIONS = "?type=ws&security=tls&sni=peer.invalid&host=cdn.invalid"
            + "&path=%2Fsocket%3Fed%3D2048%26q%3Da%2Bb&alpn=h2%2Chttp%2F1.1"
            + "&allowInsecure=0&fp=chrome&flow=xtls-rprx-vision&congestion_control=bbr"
            + "&plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dcdn.invalid#A%2BB";
    private static int checks;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }
    private static String uri(String scheme, String authority, String tail) {
        String credentials;
        if ("vless".equals(scheme)) credentials = UUID;
        else if ("tuic".equals(scheme)) credentials = UUID + ":p%40ss+%2B%3A%25";
        else if ("socks5".equals(scheme) || "socks".equals(scheme)) credentials = "user:p%40ss+%2B%3A%25";
        else if ("ss".equals(scheme)) credentials = B64.encodeUtf8("aes-128-gcm:p@ss++:%");
        else credentials = "p%40ss+%2B%3A%25";
        return scheme + "://" + credentials + "@" + authority + tail;
    }
    private static int defaultPort(String scheme) {
        if ("ss".equals(scheme)) return 8388;
        if ("socks5".equals(scheme) || "socks".equals(scheme)) return 1080;
        return 443;
    }
    private static String[] wrappers(String raw) {
        return new String[] {raw, B64.encodeUtf8(raw), B64.encodeUtf8(B64.encodeUtf8(raw)),
                "ql://" + raw, "ql:" + B64.encodeUtf8(raw), B64.encodeUtf8("ql://" + raw)};
    }
    private static Node variants(String raw, String host, int port) {
        Node expected = V2rayUriParser.parseOne(raw);
        check(expected != null, "direct URI exists: " + raw);
        check(host.equals(expected.server) && expected.port == port, "correct authority: " + raw);
        for (String input : wrappers(raw)) {
            String text = SubParser.unwrapBase64(input);
            int format = SubParser.detect(text);
            check(format == SubParser.FMT_URI || format == SubParser.FMT_QL, "known wrapper format");
            List<Node> nodes = SubParser.parseNodes(text, format);
            check(nodes.size() == 1, "one wrapped node: " + raw);
            check(nodes.get(0).toYaml().equals(expected.toYaml()), "wrapper preserves all parameters: " + raw);
        }
        String ql = QlParser.wrap(raw);
        check(QlParser.parse(ql).get(0).toYaml().equals(expected.toYaml()), "explicit conversion is lossless");
        check(ql.equals(QlParser.wrap(ql)), "explicit conversion is idempotent");
        return expected;
    }
    private static void rejects(String raw) {
        boolean rejected = false;
        try { rejected = V2rayUriParser.parseOne(raw) == null; }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "malformed endpoint rejected: " + raw);
        for (String input : wrappers(raw)) {
            String text = SubParser.unwrapBase64(input);
            check(SubParser.parseNodes(text, SubParser.detect(text)).isEmpty(),
                    "malformed wrapped endpoint is not an apparently usable node");
        }
    }
    @SuppressWarnings("unchecked")
    private static void assertOptions(String scheme, Node n) {
        check("A+B".equals(n.name), "fragment decoded once");
        if ("vless".equals(scheme) || "tuic".equals(scheme))
            check(UUID.equals(n.opts.get("uuid")), "complete UUID preserved");
        if (!"vless".equals(scheme))
            check("p@ss++:%".equals(n.opts.get("password")), "password decoded once; literal plus preserved");
        if ("vless".equals(scheme) || "trojan".equals(scheme)) {
            Map<String, Object> ws = (Map<String, Object>) n.opts.get("ws-opts");
            check("ws".equals(n.opts.get("network")) && ws != null, "WS transport retained");
            check("/socket?ed=2048&q=a+b".equals(ws.get("path")), "query transport path retained");
            check("cdn.invalid".equals(((Map<String, Object>) ws.get("headers")).get("Host")), "WS host retained");
            check(Arrays.asList("h2", "http/1.1").equals(n.opts.get("alpn")), "ALPN retained");
            check(Boolean.FALSE.equals(n.opts.get("skip-cert-verify")), "certificate validation retained");
        }
        if ("vless".equals(scheme)) {
            check(Boolean.TRUE.equals(n.opts.get("tls")), "TLS retained");
            check("peer.invalid".equals(n.opts.get("servername")), "TLS server name retained");
            check("chrome".equals(n.opts.get("client-fingerprint")), "fingerprint retained");
            check("xtls-rprx-vision".equals(n.opts.get("flow")), "flow retained");
        }
        if ("tuic".equals(scheme)) {
            check("peer.invalid".equals(n.opts.get("sni")), "TUIC SNI retained");
            check("bbr".equals(n.opts.get("congestion-controller")), "TUIC congestion controller retained");
        }
        if ("hy2".equals(scheme) || "hysteria2".equals(scheme)) {
            check("peer.invalid".equals(n.opts.get("sni")), "Hysteria2 SNI retained");
            check(Arrays.asList("h2", "http/1.1").equals(n.opts.get("alpn")), "Hysteria2 ALPN retained");
        }
        if ("ss".equals(scheme)) {
            check("aes-128-gcm".equals(n.opts.get("cipher")), "SS cipher retained");
            check("obfs".equals(n.opts.get("plugin")), "SIP002 plugin retained");
            check("cdn.invalid".equals(((Map<String, Object>) n.opts.get("plugin-opts")).get("host")), "plugin host retained");
        }
    }
    public static void main(String[] args) {
        for (String scheme : SCHEMES) {
            for (String authority : new String[] {"example.invalid", "[" + IPV6 + "]"}) {
                String host = authority.startsWith("[") ? IPV6 : authority;
                variants(uri(scheme, authority, ""), host, defaultPort(scheme));
                variants(uri(scheme, authority, "/"), host, defaultPort(scheme));
                Node original = variants(uri(scheme, authority + ":8443", OPTIONS), host, 8443);
                assertOptions(scheme, original);
                for (String path : new String[] {"/", "/path"}) {
                    Node withPath = variants(uri(scheme, authority + ":8443", path + OPTIONS), host, 8443);
                    check(withPath.toYaml().equals(original.toYaml()), "authority path cannot change port or query parameters");
                }
                variants(uri(scheme, authority + ":1", "/#min"), host, 1);
                variants(uri(scheme, authority + ":65535", "/#max"), host, 65535);
                for (String port : new String[] {"", "0", "65536", "99999999999999999", "-1", "+443", "abc", "443x"})
                    rejects(uri(scheme, authority + ":" + port, "/?sni=peer.invalid#bad"));
            }
        }
        for (String malformed : new String[] {"", "[2001:db8::1", "[2001:db8::1]junk", "2001:db8::1", ":443", "[]"})
            rejects(uri("trojan", malformed, ""));
        String ipv6 = "trojan://test@[" + IPV6 + "]";
        check(ipv6.equals(QlParser.split(ipv6).get(0)), "IPv6 closing bracket preserved exactly");
        check(ipv6.equals(QlParser.split("[" + ipv6 + "]").get(0)), "only presentation bracket removed");
        check(ipv6.equals(QlParser.split("\"" + ipv6 + "\"").get(0)), "quoted IPv6 closing bracket preserved");
        String second = uri("tuic", "second.invalid:9443", OPTIONS);
        for (String separator : new String[] {" ", "\n", "\r\n", ",", ";", "|", "", "\\n", "\\t"}) {
            for (String input : wrappers(ipv6 + separator + second)) {
                String text = SubParser.unwrapBase64(input);
                List<Node> nodes = SubParser.parseNodes(text, SubParser.detect(text));
                check(nodes.size() == 2, "mixed list preserves both nodes");
                check(IPV6.equals(nodes.get(0).server) && nodes.get(0).port == 443, "IPv6 preserved before separator");
                check(UUID.equals(nodes.get(1).opts.get("uuid")), "TUIC UUID preserved after separator");
            }
        }
        variants(ipv6 + "#node[label]", IPV6, 443);
        // HTTP(S) remains parseOne-only: do not broaden subscription format recognition.
        Node https = V2rayUriParser.parseOne("https://user:p%40ss+%2B@example.invalid:9443/#A+B");
        Node http = V2rayUriParser.parseOne("http://user:p%40ss+%2B@example.invalid:8080/#A+B");
        check("http".equals(https.type) && Boolean.TRUE.equals(https.opts.get("tls")), "HTTPS proxy retains TLS");
        check(Boolean.FALSE.equals(http.opts.get("tls")), "HTTP proxy stays plaintext");
        check(https.port == 9443 && http.port == 8080, "HTTP(S) explicit ports preserved");
        check("user".equals(https.opts.get("username")) && "p@ss++".equals(https.opts.get("password")), "HTTP(S) credentials unchanged");
        check("A+B".equals(https.name), "HTTP(S) fragment unchanged");
        check(SubParser.detect("https://user:pass@example.invalid:9443") == SubParser.FMT_UNKNOWN, "no new subscription protocol");
        check(QlParser.split("https://user:pass@example.invalid:9443").isEmpty(), "no new ql protocol");
        System.out.println("AuditUriEdges: " + checks + " checks passed; synthetic data, no Android/network/VPN");
    }
}
