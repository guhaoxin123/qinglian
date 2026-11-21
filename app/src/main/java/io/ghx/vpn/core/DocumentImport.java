package io.ghx.vpn.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

import io.ghx.vpn.sub.B64;
import io.ghx.vpn.sub.ClashYaml;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.sub.SubParser;

/** Network/Android-free document reader. Never opens paths, URIs, archives or providers. */
public final class DocumentImport {
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    public enum Problem { TOO_LARGE, EMPTY, ENCODING, BINARY, FORMAT, NO_NODES, MALFORMED }

    /** Stable, content-free reason codes: UI must localize, not display exception details. */
    public static final class ImportException extends IOException {
        public final Problem problem;
        public ImportException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }
    }

    public static final class Preview {
        public final String text;
        public final int format;
        /** -1 means this lightweight reader cannot safely count advanced YAML/provider nodes. */
        public final int nodeCount;
        private Preview(String text, int format, int nodeCount) {
            this.text = text;
            this.format = format;
            this.nodeCount = nodeCount;
        }
    }

    private DocumentImport() { }

    /** Owns and always closes in, including over-limit, decoding, read and close failures. */
    public static String read(InputStream in) throws IOException {
        if (in == null) throw new IOException("READ");
        try (InputStream owned = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                // Read at most one sentinel byte beyond the limit, never silently truncate.
                int n = owned.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - total + 1));
                if (n < 0) break;
                if (n == 0) {
                    int one = owned.read();
                    if (one < 0) break;
                    if (total == MAX_BYTES) throw new ImportException(Problem.TOO_LARGE);
                    out.write(one);
                    total++;
                } else {
                    if (n > MAX_BYTES - total) throw new ImportException(Problem.TOO_LARGE);
                    out.write(buffer, 0, n);
                    total += n;
                }
            }
            return decode(out.toByteArray());
        }
    }

    private static String decode(byte[] bytes) throws ImportException {
        int n = bytes.length;
        if (n == 0) throw new ImportException(Problem.EMPTY);
        // Archives are not expanded (no zip/gzip bombs); these are not text documents.
        if ((n >= 2 && bytes[0] == 0x1f && bytes[1] == (byte) 0x8b)
                || (n >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                    && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7))
                || (n > 262 && bytes[257] == 'u' && bytes[258] == 's'
                    && bytes[259] == 't' && bytes[260] == 'a' && bytes[261] == 'r')) {
            throw new ImportException(Problem.BINARY);
        }
        // Test UTF-32 before UTF-16: their little-endian BOMs share the first two bytes.
        if (n >= 4 && ((bytes[0] == 0 && bytes[1] == 0 && bytes[2] == (byte) 0xfe && bytes[3] == (byte) 0xff)
                || (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe && bytes[2] == 0 && bytes[3] == 0))) {
            throw new ImportException(Problem.ENCODING);
        }
        if (n >= 4 && bytes[0] == '+' && bytes[1] == '/' && bytes[2] == 'v'
                && (bytes[3] == '8' || bytes[3] == '9' || bytes[3] == '+' || bytes[3] == '/')) {
            throw new ImportException(Problem.ENCODING); // UTF-7 signature.
        }
        String charset = "UTF-8";
        int skip = 0;
        if (n >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
            skip = 3;
        } else if (n >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = "UTF-16LE";
            skip = 2;
        } else if (n >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = "UTF-16BE";
            skip = 2;
        }
        final String text;
        try {
            text = Charset.forName(charset).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, n - skip)).toString();
        } catch (CharacterCodingException error) {
            throw new ImportException(Problem.ENCODING);
        }
        checkText(text);
        return text;
    }

    /** Also bounds pasted/decoded text by its UTF-8 size without first allocating a byte copy. */
    private static void checkText(String text) throws ImportException {
        if (text == null || text.length() == 0) throw new ImportException(Problem.EMPTY);
        if (text.length() > MAX_BYTES) throw new ImportException(Problem.TOO_LARGE);
        boolean nonWhitespace = false;
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 32 && c != '\t' && c != '\r' && c != '\n')
                    || (c >= 0x7f && c <= 0x9f) || c == 0xfffe || c == 0xffff) {
                throw new ImportException(Problem.BINARY);
            }
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != '\ufeff') nonWhitespace = true;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new ImportException(Problem.ENCODING);
                }
                i++;
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new ImportException(Problem.ENCODING);
            } else bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            if (bytes > MAX_BYTES) throw new ImportException(Problem.TOO_LARGE);
        }
        if (!nonWhitespace) throw new ImportException(Problem.EMPTY);
    }

    /** Validates without persisting anything or contacting URLs embedded in the document. */
    public static Preview inspect(String source) throws ImportException {
        checkText(source);
        String text = source.trim();
        if (text.startsWith("\ufeff")) text = text.substring(1).trim();
        int format = SubParser.detect(text);
        if (format == SubParser.FMT_UNKNOWN && B64.looksLike(text)) {
            checkBase64(text);
            // Unlike SubParser's forgiving decoder, reject malformed UTF-8 in a wrapper.
            text = decode(B64.decode(text)).trim();
            if (text.startsWith("\ufeff")) text = text.substring(1).trim();
            format = SubParser.detect(text);
        }
        if (format == SubParser.FMT_UNKNOWN) throw new ImportException(Problem.FORMAT);
        final int count;
        try {
            if (format == SubParser.FMT_CLASH) count = clashCount(text);
            else {
                count = SubParser.parseNodes(text, format).size();
                if (count == 0) throw new ImportException(Problem.NO_NODES);
            }
        } catch (RuntimeException error) {
            throw new ImportException(Problem.MALFORMED);
        }
        return new Preview(text, format, count);
    }

    private static void checkBase64(String text) throws ImportException {
        int symbols = 0, padding = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\r' || c == '\n' || c == '\t') continue;
            if (c == '=') padding++;
            else {
                if (padding != 0) throw new ImportException(Problem.MALFORMED);
                symbols++;
            }
        }
        int rem = symbols % 4;
        if (padding > 2 || rem == 1 || (padding > 0 && (symbols + padding) % 4 != 0)) {
            throw new ImportException(Problem.MALFORMED);
        }
    }

    /**
     * Only reject obvious static-list errors. This is deliberately NOT a YAML validator:
     * aliases, tags, merge keys and multiline flow lists remain opaque and uncounted.
     * Providers/groups without static nodes may be valid and are never fetched here.
     */
    private static int clashCount(String text) throws ImportException {
        String[] lines = text.split("\r?\n");
        boolean providers = false, groups = false;
        int start = -1, end = lines.length;
        String tail = "";
        for (int i = 0; i < lines.length; i++) {
            String key = ClashYaml.topKey(lines[i]);
            if ("proxy-providers".equals(key)) providers = sectionHasValue(lines, i);
            if ("proxy-groups".equals(key)) groups = sectionHasValue(lines, i);
            if ("proxies".equals(key)) {
                if (start >= 0) throw new ImportException(Problem.MALFORMED);
                start = i;
                tail = ClashYaml.stripComment(lines[i].substring(lines[i].indexOf(':') + 1)).trim();
            } else if (start >= 0 && end == lines.length && key != null) end = i;
        }
        if (start < 0) return -1;
        boolean alternateSource = providers || groups;
        if ("[]".equals(tail) || "null".equals(tail) || "~".equals(tail)) {
            if (!alternateSource) throw new ImportException(Problem.NO_NODES);
            return -1;
        }
        if (tail.startsWith("*") || tail.startsWith("&") || tail.startsWith("!")) return -1;
        if (tail.length() > 0 && !tail.startsWith("[")) throw new ImportException(Problem.MALFORMED);
        boolean advanced = false;
        int items = 0, itemIndent = -1;
        if (tail.startsWith("[")) {
            StringBuilder flow = new StringBuilder(tail);
            for (int i = start + 1; i < end; i++) flow.append('\n').append(ClashYaml.stripComment(lines[i]));
            String value = flow.toString().trim();
            if (!balancedFlow(value)) throw new ImportException(Problem.MALFORMED);
            if (!value.endsWith("]")) throw new ImportException(Problem.MALFORMED);
            if (value.indexOf('\n') >= 0) return -1;
            for (String item : ClashYaml.flowList(value)) {
                items++;
                if (item.startsWith("*") || item.startsWith("&") || item.startsWith("!")) advanced = true;
                else if (!item.startsWith("{") || !item.endsWith("}")) throw new ImportException(Problem.MALFORMED);
            }
        } else {
            StringBuilder flow = null;
            for (int i = start + 1; i < end; i++) {
                String raw = ClashYaml.stripComment(lines[i]);
                String line = raw.trim();
                if (line.length() == 0 || line.equals("...") || line.equals("---")) continue;
                if (flow != null) {
                    flow.append('\n').append(line);
                    if (balancedFlow(flow.toString())) flow = null;
                    continue;
                }
                int indent = 0;
                while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
                if (line.startsWith("- ") || line.equals("-")) {
                    if (itemIndent < 0) itemIndent = indent;
                    if (indent != itemIndent) continue;
                    items++;
                    String item = line.substring(1).trim();
                    if (item.startsWith("*") || item.startsWith("&") || item.startsWith("!")) advanced = true;
                    else if (item.startsWith("{")) {
                        if (!balancedFlow(item)) { flow = new StringBuilder(item); advanced = true; }
                    } else if (item.length() > 0 && item.indexOf(':') < 1) throw new ImportException(Problem.MALFORMED);
                    if (item.startsWith("<<:")) advanced = true;
                } else if (itemIndent < 0) {
                    if (line.startsWith("*") || line.startsWith("&") || line.startsWith("!")) return -1;
                    throw new ImportException(Problem.MALFORMED);
                } else if (line.startsWith("<<:")) advanced = true;
            }
            if (flow != null) throw new ImportException(Problem.MALFORMED);
        }
        if (items == 0) {
            if (!alternateSource) throw new ImportException(Problem.NO_NODES);
            return -1;
        }
        if (advanced) return -1;
        java.util.List<Node> nodes = ClashYaml.proxies(text);
        int count = nodes.size();
        if (count != items) throw new ImportException(Problem.MALFORMED);
        for (Node node : nodes) {
            if (missing(node.name) || missing(node.type)) throw new ImportException(Problem.MALFORMED);
            // Endpoint/protocol semantics are deferred to the core. This view cannot resolve
            // aliases or non-decimal YAML port scalars and must not reject otherwise valid data.
        }
        return providers ? -1 : count;
    }

    private static boolean missing(String value) {
        // ClashYaml.scalar removes quotes, so literal names such as "null" and "~" are data.
        return value == null || value.trim().length() == 0;
    }

    private static boolean sectionHasValue(String[] lines, int index) {
        String tail = ClashYaml.stripComment(lines[index].substring(lines[index].indexOf(':') + 1)).trim();
        if ("[]".equals(tail) || "{}".equals(tail) || "null".equals(tail) || "~".equals(tail)) return false;
        if (tail.length() > 0) return true;
        for (int i = index + 1; i < lines.length; i++) {
            if (ClashYaml.topKey(lines[i]) != null) return false;
            String line = ClashYaml.stripComment(lines[i]).trim();
            if (line.length() > 0 && !"...".equals(line) && !"---".equals(line)) return true;
        }
        return false;
    }

    /** Check only collection/quote balance, including multiline YAML flow collections. */
    private static boolean balancedFlow(String text) {
        StringBuilder stack = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') { i++; continue; }
                if (c == quote) {
                    if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                    else quote = 0;
                }
            } else if ((c == '\'' || c == '"') && (i == 0 || Character.isWhitespace(text.charAt(i - 1))
                    || ":,[{".indexOf(text.charAt(i - 1)) >= 0)) quote = c;
            else if (c == '[' || c == '{') stack.append(c);
            else if (c == ']' || c == '}') {
                int last = stack.length() - 1;
                if (last < 0 || stack.charAt(last) != (c == ']' ? '[' : '{')) return false;
                stack.setLength(last);
            }
        }
        return quote == 0 && stack.length() == 0;
    }
}
