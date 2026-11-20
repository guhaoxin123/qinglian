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
        System.out.println("AuditSubTest: " + checks + " checks passed");
    }
}
