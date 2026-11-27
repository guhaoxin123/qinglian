import io.ghx.vpn.sub.B64;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.sub.SubParser;
import io.ghx.vpn.core.DocumentImport;
import java.util.List;

/** Synthetic inputs only: no subscriptions, networking, JNI or VPN. */
public final class AuditFormatRegression {
    private static int checks, failures;
    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + name); }
    }
    private static void uri(String name, String raw, int expected) throws Exception {
        String text = SubParser.unwrapBase64(raw);
        int format = SubParser.detect(text);
        check(name + " format is v2ray, never ql", format == SubParser.FMT_URI);
        check(name + " badge", "v2ray".equals(SubParser.formatLabel(format, false)));
        List<Node> nodes = SubParser.parseNodes(text, format);
        check(name + " retains node count", nodes.size() == expected);
        if (nodes.size() == 2) {
            check(name + " retains first node", "A".equals(nodes.get(0).name)
                    && "a.example.invalid".equals(nodes.get(0).server));
            check(name + " retains second node", "B".equals(nodes.get(1).name)
                    && "b.example.invalid".equals(nodes.get(1).server));
        }
        DocumentImport.Preview preview = DocumentImport.inspect(raw);
        check(name + " import format agrees", preview.format == SubParser.FMT_URI);
        check(name + " import count agrees", preview.nodeCount == expected);
        check(name + " import content is not reserialized", preview.text.equals(text));
    }
    public static void main(String[] args) throws Exception {
        String first = "trojan://test@a.example.invalid:443#A";
        String different = "ss://" + B64.encodeUtf8("aes-128-gcm:test") + "@b.example.invalid:8388#B";
        String same = "trojan://other@b.example.invalid:8443#B";
        String[] separators = {"\n", "\r\n", "\r", " ", "\t", ",", ";", "|", "", "\\n", "\\r\\n", "\\t"};
        for (int i = 0; i < separators.length; i++) {
            for (String second : new String[] {same, different}) {
                String input = first + separators[i] + second;
                String name = "separator " + i + (second == same ? " same protocol" : " mixed protocols");
                uri(name, input, 2);
                uri(name + " base64", B64.encodeUtf8(input), 2);
                uri(name + " double base64", B64.encodeUtf8(B64.encodeUtf8(input)), 2);
            }
        }
        uri("single URI", first, 1);
        for (String prefix : new String[] {"ql://", "ql:", "QL://"}) {
            for (String body : new String[] {first + " " + different, B64.encodeUtf8(first + "\n" + different)}) {
                String wrapped = prefix + body;
                check("explicit wrapper remains ql", SubParser.detect(wrapped) == SubParser.FMT_QL);
                check("explicit wrapper retains two nodes", SubParser.parseNodes(wrapped, SubParser.FMT_QL).size() == 2);
                check("explicit wrapper preview agrees", DocumentImport.inspect(wrapped).format == SubParser.FMT_QL);
            }
        }
        check("empty ql is unknown", SubParser.detect("ql://") == SubParser.FMT_UNKNOWN);
        check("invalid ql is unknown", SubParser.detect("ql://not a node") == SubParser.FMT_UNKNOWN);
        System.out.println("AuditFormatRegression: " + checks + " checks, " + failures + " failures");
        if (failures != 0) throw new AssertionError("v2ray misclassification regression");
    }
}
