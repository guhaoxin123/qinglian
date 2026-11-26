import io.ghx.vpn.sub.B64;
import io.ghx.vpn.sub.ClashYaml;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.sub.SubParser;

/** Plain-Java regressions; compile with app/src/main/java/io/ghx/vpn/sub/*.java. */
public final class AuditSubTest {
    private static int checks;

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        String clash = "proxies:\n"
                + "  - {name: example, type: ss, server: example.invalid, port: 443, cipher: aes-128-gcm, password: test}\n"
                + "proxy-groups:\n  - {name: Test, type: select, proxies: [example]}\n";
        String wrappedClash = B64.encodeUtf8(clash);
        String decoded = SubParser.unwrapBase64(wrappedClash);
        check("wrapped Clash is decoded, not just classified", decoded.equals(clash.trim()));
        check("decoded Clash detects as Clash", SubParser.detect(decoded) == SubParser.FMT_CLASH);
        check("decoded Clash contains its proxy", ClashYaml.proxies(decoded).size() == 1);
        check("plaintext detection does not classify encoded YAML", SubParser.detect(wrappedClash) == SubParser.FMT_UNKNOWN);
        check("plain Clash remains unchanged", SubParser.unwrapBase64(clash).equals(clash.trim()));
        check("decode is idempotent", SubParser.unwrapBase64(decoded).equals(decoded));

        String folded = wrappedClash.substring(0, 25) + "\r\n  " + wrappedClash.substring(25);
        check("folded base64 Clash", SubParser.unwrapBase64(folded).equals(clash.trim()));
        String urlSafe = wrappedClash.replace('+', '-').replace('/', '_').replace("=", "");
        check("unpadded URL-safe base64 Clash", SubParser.unwrapBase64(urlSafe).equals(clash.trim()));

        String uris = "trojan://test@example.invalid:443#Example\n"
                + "ss://" + B64.encodeUtf8("aes-128-gcm:test") + "@ss.example.invalid:8388#SS";
        String decodedUris = SubParser.unwrapBase64(B64.encodeUtf8(uris));
        check("wrapped URI list is decoded", decodedUris.equals(uris));
        check("wrapped URI list retains nodes", SubParser.parseNodes(decodedUris,
                SubParser.detect(decodedUris)).size() == 2);
        check("plain URI remains unchanged", SubParser.unwrapBase64(uris).equals(uris));

        String surf = "[Proxy]\nExample = trojan, example.invalid, 443, password=test\n";
        String decodedSurf = SubParser.unwrapBase64(B64.encodeUtf8(surf));
        check("wrapped Surfboard is decoded", decodedSurf.equals(surf.trim()));
        check("wrapped Surfboard retains node", SubParser.parseNodes(decodedSurf,
                SubParser.detect(decodedSurf)).size() == 1);

        check("UTF-8 BOM removed from plain YAML", SubParser.unwrapBase64("\uFEFF" + clash).equals(clash.trim()));
        check("UTF-8 BOM removed from decoded YAML", SubParser.unwrapBase64(
                B64.encodeUtf8("\uFEFF" + clash)).equals(clash.trim()));
        check("BOM removed from URI input", SubParser.parseNodes(SubParser.unwrapBase64("\uFEFF" + uris),
                SubParser.FMT_URI).size() == 2);
        check("null input is safe", SubParser.unwrapBase64(null).equals(""));
        check("empty input stays unknown", SubParser.detect(SubParser.unwrapBase64(" \n")) == SubParser.FMT_UNKNOWN);
        String unknown = "This is not a subscription";
        String encodedUnknown = B64.encodeUtf8(unknown);
        check("unknown base64 remains unchanged", SubParser.unwrapBase64(encodedUnknown).equals(encodedUnknown));
        check("HTML remains unknown", SubParser.detect("<!doctype html><html>error</html>") == SubParser.FMT_UNKNOWN);

        // ---- ql 混合格式：一段文本里混排 2 个、3 个或多个不同协议的链接 ----
        String ssLink = "ss://" + B64.encodeUtf8("aes-128-gcm:test") + "@ss.example.invalid:8388#S1";
        String ql2 = "trojan://test@example.invalid:443#T1 " + ssLink;
        check("ql: two links on one line detect as ql", SubParser.detect(ql2) == SubParser.FMT_QL);
        check("ql: two mixed links parse to 2 nodes",
                SubParser.parseNodes(ql2, SubParser.detect(ql2)).size() == 2);
        check("ql: comma separator works",
                SubParser.detect("trojan://test@example.invalid:443#T1," + ssLink) == SubParser.FMT_QL);
        check("ql: semicolon separator works",
                SubParser.detect("trojan://test@example.invalid:443#T1;" + ssLink) == SubParser.FMT_QL);
        check("ql: pipe separator works",
                SubParser.detect("trojan://test@example.invalid:443#T1|" + ssLink) == SubParser.FMT_QL);

        String ql3 = "vless://11111111-1111-1111-1111-111111111111@v.example.invalid:443"
                + "?type=ws&security=tls&path=%2Fws#V1"
                + "hysteria2://pass@h.example.invalid:8443?sni=h.example.invalid#H1"
                + ssLink;
        java.util.List<Node> mixed = SubParser.parseNodes(ql3, SubParser.detect(ql3));
        check("ql: three glued links detect as ql", SubParser.detect(ql3) == SubParser.FMT_QL);
        check("ql: three glued links parse to 3 nodes", mixed.size() == 3);
        check("ql: glued links keep distinct protocols",
                "vless".equals(mixed.get(0).type) && "hysteria2".equals(mixed.get(1).type)
                        && "ss".equals(mixed.get(2).type));
        check("ql: glued links keep their own names",
                "V1".equals(mixed.get(0).name) && "H1".equals(mixed.get(1).name)
                        && "S1".equals(mixed.get(2).name));

        check("ql: JSON-escaped newline separator works",
                SubParser.detect(ql2.replace(" ", "\\n")) == SubParser.FMT_QL);

        String qlWrapped = "ql://" + B64.encodeUtf8(ql3);
        check("ql: ql:// wrapper keeps all nodes",
                SubParser.parseNodes(qlWrapped, SubParser.detect(qlWrapped)).size() == 3);
        String qlPlainWrapper = "ql://" + ql2;
        check("ql: ql:// plaintext wrapper detects as ql",
                SubParser.detect(qlPlainWrapper) == SubParser.FMT_QL
                        && SubParser.parseNodes(qlPlainWrapper, SubParser.FMT_QL).size() == 2);

        // 老格式不受影响：每行一条仍是普通 URI 列表，单条链接也不会被误判成 ql
        check("ql: one link per line stays plain URI list",
                SubParser.detect("trojan://test@example.invalid:443#T1\n" + ssLink) == SubParser.FMT_URI);
        check("ql: single link stays plain URI",
                SubParser.detect("trojan://test@example.invalid:443#T1") == SubParser.FMT_URI);
        check("ql: single link keeps parsing", SubParser.parseNodes(ssLink, SubParser.FMT_URI).size() == 1);
        check("ql: mixed line also parses under plain URI",
                SubParser.parseNodes(ql2, SubParser.FMT_URI).size() == 2);
        check("ql: base64-wrapped mix unwraps and detects",
                SubParser.detect(SubParser.unwrapBase64(B64.encodeUtf8(ql3))) == SubParser.FMT_QL);
        check("ql: double base64 wrapper unwraps",
                SubParser.detect(SubParser.unwrapBase64(B64.encodeUtf8(B64.encodeUtf8(ql3)))) == SubParser.FMT_QL);
        check("ql: unknown text is still unknown",
                SubParser.detect("ql://not a link at all") == SubParser.FMT_UNKNOWN);

        // Protocol summaries are not subscription-format labels.
        java.util.List<Node> mixedNodes = SubParser.parseNodes(
                "vmess://" + B64.encodeUtf8("{\"v\":\"2\",\"ps\":\"a\",\"add\":\"a.invalid\",\"port\":\"443\","
                        + "\"id\":\"11111111-1111-1111-1111-111111111111\",\"aid\":\"0\",\"net\":\"ws\"}")
                        + "\ntrojan://pass@b.invalid:443#b", SubParser.FMT_URI);
        check("label: mixed links parse into different protocols", mixedNodes.size() == 2
                && !mixedNodes.get(0).type.equals(mixedNodes.get(1).type));
        check("label: mixed protocols have no single kind",
                io.ghx.vpn.sub.NodeKind.uniform(mixedNodes) == null);
        check("label: a single protocol is named",
                "trojan".equals(io.ghx.vpn.sub.NodeKind.uniform(
                        SubParser.parseNodes("trojan://pass@b.invalid:443#b", SubParser.FMT_URI))));
        check("label: an empty list has no kind",
                io.ghx.vpn.sub.NodeKind.uniform(new java.util.ArrayList<Node>()) == null);
        check("label: a type-less node is not summarised",
                io.ghx.vpn.sub.NodeKind.uniform(
                        java.util.Arrays.asList(new Node(null, "x", "h", 1))) == null);
        check("format: mixed ordinary URIs remain v2ray",
                "v2ray".equals(SubParser.formatLabel(SubParser.detect(uris), false)));
        check("format: base64 mixed ordinary URIs remain v2ray",
                "v2ray".equals(SubParser.formatLabel(SubParser.detect(decodedUris), true)));
        check("format: one trojan remains URI format, not protocol badge",
                "v2ray".equals(SubParser.formatLabel(SubParser.detect("trojan://p@b.invalid:443#b"), false)));
        check("format: explicit ql wrapper stays ql",
                "ql 混合".equals(SubParser.formatLabel(SubParser.detect(qlWrapped), false)));
        check("format: English ql label", "ql mixed".equals(SubParser.formatLabel(SubParser.FMT_QL, true)));
        check("format: Clash label preserved", "Clash".equals(SubParser.formatLabel(SubParser.FMT_CLASH, false)));
        check("format: Surfboard label preserved", "Surfboard".equals(SubParser.formatLabel(SubParser.FMT_SURFBOARD, false)));
        check("format: unrecognized URL has no guessed label", "待识别".equals(SubParser.formatLabel(0, false)));
        check("format: unknown English label", "Unknown".equals(SubParser.formatLabel(-1, true)));
        System.out.println("AuditSubTest: " + checks + " checks passed");
    }
}
