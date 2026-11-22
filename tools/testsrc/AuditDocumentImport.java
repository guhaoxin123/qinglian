import io.ghx.vpn.core.DocumentImport;
import io.ghx.vpn.core.DocumentImport.ImportException;
import io.ghx.vpn.core.DocumentImport.Problem;
import io.ghx.vpn.sub.B64;
import io.ghx.vpn.sub.SubParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/** Pure JVM tests: compile sub/*.java, core/DocumentImport.java and this file. No Android/native I/O. */
public final class AuditDocumentImport {
    private static int checks;
    private static final String CLASH = "proxies:\n"
            + "  - {name: 测试 Node, type: ss, server: example.invalid, port: 443, cipher: aes-128-gcm, password: test}\n"
            + "proxy-groups:\n  - {name: Test, type: select, proxies: [测试 Node]}\n";

    private static void check(String name, boolean condition) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }

    private static byte[] bytes(String text, String charset) { return text.getBytes(Charset.forName(charset)); }

    private static byte[] prefix(byte[] head, byte[] body) {
        byte[] all = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    private static final class Tracked extends ByteArrayInputStream {
        boolean closed;
        Tracked(byte[] data) { super(data); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static void rejectsBytes(String name, byte[] data, Problem problem) throws Exception {
        Tracked in = new Tracked(data);
        try { DocumentImport.read(in); throw new AssertionError(name + " accepted"); }
        catch (ImportException error) {
            check(name + " reason", error.problem == problem);
            check(name + " exception reveals no payload", error.getMessage().equals(problem.name()));
        }
        check(name + " closes input", in.closed);
    }

    private static void rejectsText(String name, String text, Problem problem) throws Exception {
        try { DocumentImport.inspect(text); throw new AssertionError(name + " accepted"); }
        catch (ImportException error) { check(name, error.problem == problem); }
    }

    private static final class Generated extends InputStream {
        final long size;
        long read;
        boolean closed;
        Generated(long size) { this.size = size; }
        @Override public int read() {
            if (read >= size) return -1;
            read++;
            return 'x';
        }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (read >= size) return -1;
            int count = (int) Math.min(length, size - read);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            read += count;
            return count;
        }
        @Override public void close() { closed = true; }
    }

    public static void main(String[] args) throws Exception {
        Tracked plain = new Tracked(bytes(CLASH, "UTF-8"));
        check("UTF-8 preserves all content and names", DocumentImport.read(plain).equals(CLASH));
        check("success closes stream", plain.closed);
        check("UTF-8 BOM", DocumentImport.read(new Tracked(prefix(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                bytes(CLASH, "UTF-8")))).equals(CLASH));
        check("UTF-16LE BOM", DocumentImport.read(new Tracked(prefix(new byte[] {(byte) 0xff, (byte) 0xfe},
                bytes(CLASH, "UTF-16LE")))).equals(CLASH));
        check("UTF-16BE BOM", DocumentImport.read(new Tracked(prefix(new byte[] {(byte) 0xfe, (byte) 0xff},
                bytes(CLASH, "UTF-16BE")))).equals(CLASH));
        String astral = "trojan://test@example.invalid:443#🙂 节点";
        check("UTF-8 astral unicode", DocumentImport.read(new Tracked(bytes(astral, "UTF-8"))).equals(astral));
        check("UTF-16 astral unicode", DocumentImport.read(new Tracked(prefix(new byte[] {(byte) 0xff, (byte) 0xfe},
                bytes(astral, "UTF-16LE")))).equals(astral));
        rejectsBytes("zero bytes", new byte[0], Problem.EMPTY);
        rejectsBytes("whitespace", bytes(" \t\r\n", "UTF-8"), Problem.EMPTY);
        rejectsBytes("unicode whitespace", bytes("\u3000\u00a0", "UTF-8"), Problem.EMPTY);
        rejectsBytes("BOM only", new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, Problem.EMPTY);
        rejectsBytes("UTF-16 BOM only", new byte[] {(byte) 0xfe, (byte) 0xff}, Problem.EMPTY);
        rejectsBytes("NUL binary", new byte[] {'p', 0, 'x'}, Problem.BINARY);
        rejectsBytes("binary control", new byte[] {'p', 1, 'x'}, Problem.BINARY);
        rejectsBytes("UTF-16 without BOM", bytes("proxies:\n  - ASCII", "UTF-16LE"), Problem.BINARY);
        rejectsBytes("ZIP rejected not decompressed", new byte[] {'P', 'K', 3, 4}, Problem.BINARY);
        rejectsBytes("gzip rejected not decompressed", new byte[] {0x1f, (byte) 0x8b, 8}, Problem.BINARY);
        rejectsBytes("invalid UTF-8", new byte[] {(byte) 0xc0, (byte) 0xaf}, Problem.ENCODING);
        rejectsBytes("truncated UTF-8", new byte[] {'x', (byte) 0xe2, (byte) 0x82}, Problem.ENCODING);
        rejectsBytes("truncated BOM", new byte[] {(byte) 0xef, (byte) 0xbb}, Problem.ENCODING);
        rejectsBytes("truncated UTF-16 code unit", new byte[] {(byte) 0xff, (byte) 0xfe, 65}, Problem.ENCODING);
        rejectsBytes("unpaired UTF-16 surrogate", new byte[] {(byte) 0xff, (byte) 0xfe, 0, (byte) 0xd8}, Problem.ENCODING);
        rejectsBytes("UTF-32LE BOM", new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0, 65, 0, 0, 0}, Problem.ENCODING);
        rejectsBytes("UTF-32BE BOM", new byte[] {0, 0, (byte) 0xfe, (byte) 0xff, 0, 0, 0, 65}, Problem.ENCODING);
        rejectsBytes("UTF-7 signature", bytes("+/v8-proxies:", "UTF-8"), Problem.ENCODING);
        rejectsBytes("legacy encoding rejected", bytes("节点", "GB18030"), Problem.ENCODING);

        Generated exact = new Generated(DocumentImport.MAX_BYTES);
        check("exact 8 MiB accepted without truncation", DocumentImport.read(exact).length() == DocumentImport.MAX_BYTES);
        check("exact size closed", exact.closed);
        Generated enormous = new Generated(Long.MAX_VALUE);
        try { DocumentImport.read(enormous); throw new AssertionError("oversize accepted"); }
        catch (ImportException error) { check("oversize reason", error.problem == Problem.TOO_LARGE); }
        check("oversize consumes only limit plus sentinel", enormous.read == DocumentImport.MAX_BYTES + 1L);
        check("oversize closes stream", enormous.closed);
        final boolean[] closeOnReadFailure = {false};
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("synthetic read failure"); }
            @Override public void close() { closeOnReadFailure[0] = true; }
        };
        try { DocumentImport.read(broken); throw new AssertionError("read failure accepted"); }
        catch (IOException expected) { check("read failure closes", closeOnReadFailure[0]); }
        InputStream zeroRead = new ByteArrayInputStream(bytes(CLASH, "UTF-8")) {
            boolean first = true;
            @Override public synchronized int read(byte[] data, int offset, int length) {
                if (first) { first = false; return 0; }
                return super.read(data, offset, length);
            }
        };
        check("zero-length provider read is not mistaken for EOF", DocumentImport.read(zeroRead).equals(CLASH));
        final boolean[] closeAttempt = {false};
        InputStream closeFailure = new ByteArrayInputStream(bytes(CLASH, "UTF-8")) {
            @Override public void close() throws IOException { closeAttempt[0] = true; throw new IOException("close"); }
        };
        try { DocumentImport.read(closeFailure); throw new AssertionError("close failure accepted"); }
        catch (IOException expected) { check("close failure reported", closeAttempt[0]); }
        try { DocumentImport.read(null); throw new AssertionError("null input accepted"); }
        catch (IOException expected) { check("null provider stream handled", true); }

        DocumentImport.Preview clash = DocumentImport.inspect(CLASH);
        check("Clash format", clash.format == SubParser.FMT_CLASH);
        check("Clash count", clash.nodeCount == 1);
        check("Clash not reserialized", clash.text.equals(CLASH.trim()));
        check("base64 Clash", DocumentImport.inspect(B64.encodeUtf8(CLASH)).nodeCount == 1);
        check("base64 URI", DocumentImport.inspect(B64.encodeUtf8(astral)).nodeCount == 1);
        check("URI format", DocumentImport.inspect(astral).format == SubParser.FMT_URI);
        String qlMix = "trojan://test@example.invalid:443#T1 ss://"
                + B64.encodeUtf8("aes-128-gcm:test") + "@ss.example.invalid:8388#S1"
                + "hysteria2://pass@h.example.invalid:8443#H1";
        DocumentImport.Preview ql = DocumentImport.inspect(qlMix);
        check("ql mixed links import as ql", ql.format == SubParser.FMT_QL);
        check("ql mixed links count every node", ql.nodeCount == 3);
        check("ql wrapper imports", DocumentImport.inspect("ql://" + B64.encodeUtf8(qlMix)).nodeCount == 3);
        check("ql double base64 imports",
                DocumentImport.inspect(B64.encodeUtf8(B64.encodeUtf8(qlMix))).nodeCount == 3);
        check("Surfboard", DocumentImport.inspect("[Proxy]\nExample = trojan, example.invalid, 443, password=test").nodeCount == 1);
        rejectsText("empty paste", " \n", Problem.EMPTY);
        rejectsText("unpaired surrogate paste", "proxies:\n\ud800", Problem.ENCODING);
        rejectsText("unknown text", "not: a configuration", Problem.FORMAT);
        rejectsText("HTML", "<!doctype html><html>login</html>", Problem.FORMAT);
        rejectsText("empty static flow", "proxies: []", Problem.NO_NODES);
        rejectsText("empty static block", "proxies:\n# no nodes", Problem.NO_NODES);
        rejectsText("null static", "proxies: null", Problem.NO_NODES);
        rejectsText("all lists empty", "proxies: []\nproxy-groups: []\nproxy-providers: {}", Problem.NO_NODES);
        rejectsText("static scalar", "proxies: nonsense", Problem.MALFORMED);
        rejectsText("static mapping not list", "proxies:\n  name: x\n  type: ss", Problem.MALFORMED);
        rejectsText("scalar item", "proxies:\n  - nonsense", Problem.MALFORMED);
        rejectsText("empty object", "proxies: [{}]", Problem.MALFORMED);
        rejectsText("missing type", "proxies:\n  - {name: broken}", Problem.MALFORMED);
        // Basic shape checks are not protocol/core validation; endpoint semantics are deferred.
        check("missing endpoint deferred to core", DocumentImport.inspect(
                "proxies:\n  - {name: broken, type: ss}").nodeCount == 1);
        check("invalid port deferred to core", DocumentImport.inspect(
                "proxies:\n  - {name: broken, type: ss, server: example.invalid, port: 99999}").nodeCount == 1);
        rejectsText("truncated flow", "proxies: [{name: broken, type: ss}", Problem.MALFORMED);
        rejectsText("truncated block flow", "proxies:\n  - {name: broken, type: ss", Problem.MALFORMED);
        rejectsText("duplicate proxies", CLASH + "proxies: []\n", Problem.MALFORMED);
        byte[] badWrapped = prefix(bytes(CLASH, "UTF-8"), new byte[] {(byte) 0xc3});
        rejectsText("base64 malformed text", B64.encode(badWrapped), Problem.ENCODING);
        rejectsText("base64 invalid padding", B64.encodeUtf8(CLASH) + "=", Problem.MALFORMED);
        String provider = "proxies: []\nproxy-providers:\n  remote:\n    type: http\n"
                + "    url: https://example.invalid/never-fetched.yaml\nproxy-groups:\n"
                + "  - {name: Test, type: select, use: [remote]}\n";
        check("provider Clash accepted without fetching", DocumentImport.inspect(provider).nodeCount == -1);
        check("direct-only group config remains accepted", DocumentImport.inspect("proxy-groups:\n"
                + "  - {name: Direct, type: select, proxies: [DIRECT]}\n").nodeCount == -1);
        check("direct static node accepted", DocumentImport.inspect("proxies: [{name: Direct, type: direct}]").nodeCount == 1);
        check("alias list remains accepted", DocumentImport.inspect("proxies: *shared\nproxy-groups: []").nodeCount == -1);
        check("merge key remains accepted", DocumentImport.inspect("proxies:\n  - <<: *base\n    name: N\n").nodeCount == -1);
        check("multiline flow remains accepted", DocumentImport.inspect("proxies: [\n"
                + "  {name: n, type: ss, server: example.invalid, port: 443}\n]\n").nodeCount == -1);
        check("multiline mapping remains accepted", DocumentImport.inspect("proxies:\n"
                + "  - {name: n,\n    type: ss, server: example.invalid, port: 443}\n").nodeCount == -1);
        check("ordinary block proxy accepted", DocumentImport.inspect("proxies:\n"
                + "  - name: original name\n    type: ss\n    server: example.invalid\n    port: 443\n").nodeCount == 1);
        String literalNames = "proxies:\n"
                + "  - {name: \"null\", type: direct}\n"
                + "  - {name: '~', type: direct}\n";
        DocumentImport.Preview literals = DocumentImport.inspect(literalNames);
        check("quoted null and tilde names remain valid data", literals.nodeCount == 2);
        check("quoted literal names preserved verbatim", literals.text.equals(literalNames.trim()));
        String hexPort = "proxies:\n"
                + "  - {name: hex port, type: ss, server: example.invalid, port: 0x1bb}\n";
        DocumentImport.Preview hex = DocumentImport.inspect(hexPort);
        check("valid YAML hexadecimal port not rejected", hex.nodeCount == 1);
        check("hexadecimal port preserved verbatim", hex.text.equals(hexPort.trim()));
        String anchorPort = "shared-port: &port 443\nproxies:\n"
                + "  - {name: anchor port, type: ss, server: example.invalid, port: *port}\n";
        DocumentImport.Preview anchor = DocumentImport.inspect(anchorPort);
        check("valid YAML anchor port not rejected", anchor.nodeCount == 1);
        check("port anchor preserved verbatim", anchor.text.equals(anchorPort.trim()));
        char[] huge = new char[DocumentImport.MAX_BYTES + 1];
        Arrays.fill(huge, 'x');
        rejectsText("paste bound", new String(huge), Problem.TOO_LARGE);
        char[] expanded = new char[DocumentImport.MAX_BYTES / 3 + 1];
        Arrays.fill(expanded, '中');
        rejectsText("decoded UTF-8 bound", new String(expanded), Problem.TOO_LARGE);
        System.out.println("AuditDocumentImport: " + checks + " checks passed");
    }
}
