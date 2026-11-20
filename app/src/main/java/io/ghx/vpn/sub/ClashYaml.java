package io.ghx.vpn.sub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, read-only view of ordinary Clash block/flow lists; never reserializes proxies. */
public final class ClashYaml {
    private ClashYaml() { }

    public static final class Group {
        public String name;
        public String type;
        public final List<String> proxies = new ArrayList<String>();
        public boolean selectable() { return "select".equals(type); }
    }

    public static List<Node> proxies(String yaml) {
        List<Node> out = new ArrayList<Node>();
        for (Map<String, String> m : section(yaml, "proxies")) {
            if (m.get("name") == null || m.get("type") == null) continue;
            out.add(new Node(m.get("type"), m.get("name"), m.get("server"), intOf(m.get("port"), 0)));
        }
        return out;
    }

    public static List<Group> groups(String yaml) {
        List<Group> out = new ArrayList<Group>();
        for (Map<String, String> m : section(yaml, "proxy-groups")) {
            if (m.get("name") == null) continue;
            Group g = new Group();
            g.name = m.get("name");
            g.type = m.get("type");
            if (m.get("proxies") != null) {
                for (String s : flowList(m.get("proxies"))) g.proxies.add(scalar(s));
            }
            out.add(g);
        }
        return out;
    }

    /** Names only: protocol-specific fields remain in the original YAML for the core. */
    public static List<String> names(String yaml, String key) {
        List<String> out = new ArrayList<String>();
        for (Map<String, String> m : section(yaml, key)) {
            if (m.get("name") == null) {
                throw new IllegalArgumentException("Unsupported or unnamed item in " + key);
            }
            out.add(m.get("name"));
        }
        return out;
    }

    private static List<Map<String, String>> section(String yaml, String key) {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        if (yaml == null) return out;
        boolean inside = false;
        int itemIndent = -1, fieldIndent = -1;
        Map<String, String> cur = null;
        String listKey = null;
        for (String raw : yaml.split("\\r?\\n")) {
            String top = topKey(raw);
            if (top != null) {
                if (inside) break;
                if (!key.equals(top)) continue;
                inside = true;
                String tail = stripComment(raw.substring(raw.indexOf(':') + 1)).trim();
                if (tail.startsWith("[")) {
                    for (String item : flowList(tail)) {
                        Map<String, String> m = new LinkedHashMap<String, String>();
                        parseFlow(item, m);
                        out.add(m);
                    }
                    break;
                }
                if (tail.length() != 0) {
                    throw new IllegalArgumentException("Unsupported " + key + " value; expected block or flow list");
                }
                continue;
            }
            if (!inside) continue;
            String t = stripComment(raw).trim();
            if (t.length() == 0 || t.equals("---") || t.equals("...")) continue;
            int indent = indentation(raw);
            if (t.startsWith("- ") || t.equals("-")) {
                if (itemIndent < 0) itemIndent = indent;
                if (indent == itemIndent) {
                    cur = new LinkedHashMap<String, String>();
                    out.add(cur);
                    fieldIndent = itemIndent + 2;
                    listKey = null;
                    String item = t.substring(1).trim();
                    if (item.startsWith("{")) parseFlow(item, cur);
                    else parsePair(item, cur);
                } else if (cur != null && listKey != null && indent >= fieldIndent) {
                    String value = cur.get(listKey);
                    if (value == null || value.length() == 0) value = "[]";
                    String body = value.substring(0, value.length() - 1);
                    cur.put(listKey, body + (body.length() > 1 ? ", " : "") + t.substring(1).trim() + "]");
                }
            } else if (cur != null && indent == fieldIndent) {
                parsePair(t, cur);
                int colon = t.indexOf(':');
                listKey = colon > 0 && t.substring(colon + 1).trim().length() == 0
                        ? scalar(t.substring(0, colon)) : null;
            }
        }
        return out;
    }

    /** A real root mapping key; comments and unindented sequence entries are not keys. */
    public static String topKey(String line) {
        if (line.length() == 0 || Character.isWhitespace(line.charAt(0))) return null;
        String s = stripComment(line).trim();
        if (s.startsWith("-") || s.length() == 0) return null;
        int c = s.indexOf(':');
        if (c <= 0) return null;
        return scalar(s.substring(0, c));
    }

    private static int indentation(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static void parseFlow(String s, Map<String, String> into) {
        String body = stripComment(s).trim();
        if (!body.startsWith("{") || !body.endsWith("}")) return;
        for (String part : splitTop(body.substring(1, body.length() - 1))) parsePair(part, into);
    }

    private static void parsePair(String s, Map<String, String> into) {
        int c = s.indexOf(':');
        if (c <= 0) return;
        into.put(scalar(s.substring(0, c)), scalar(s.substring(c + 1)));
    }

    /** Raw comma-separated flow items, respecting quoted strings and nested collections. */
    public static List<String> flowList(String s) {
        s = stripComment(s).trim();
        if (s.startsWith("[")) {
            if (!s.endsWith("]")) throw new IllegalArgumentException("Unsupported multiline or unclosed flow list");
            s = s.substring(1, s.length() - 1);
        }
        return splitTop(s);
    }

    private static List<String> splitTop(String s) {
        List<String> out = new ArrayList<String>();
        int start = 0, depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') { i++; continue; }
                if (c == quote) {
                    if (quote == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') i++;
                    else quote = 0;
                }
            } else if ((c == '\'' || c == '"') && startsQuote(s, i)) quote = c;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (s.substring(start).trim().length() > 0) out.add(s.substring(start).trim());
        return out;
    }

    private static boolean startsQuote(String s, int i) {
        if (i == 0) return true;
        char previous = s.charAt(i - 1);
        return Character.isWhitespace(previous) || previous == ':' || previous == ','
                || previous == '[' || previous == '{';
    }

    public static String stripComment(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') { i++; continue; }
                if (c == quote) {
                    if (quote == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') i++;
                    else quote = 0;
                }
            } else if ((c == '\'' || c == '"') && startsQuote(s, i)) quote = c;
            else if (c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) return s.substring(0, i);
        }
        return s;
    }

    /** Decode the YAML scalar forms used by proxy names and quoted rules. */
    public static String scalar(String value) {
        String v = stripComment(value).trim();
        if (v.length() < 2) return v;
        if (v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        if (v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') return v;
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length() - 1) {
                c = v.charAt(++i);
                if (c == 'n') c = '\n';
                else if (c == 'r') c = '\r';
                else if (c == 't') c = '\t';
                else if (c == 'u' || c == 'U' || c == 'x') {
                    int count = c == 'U' ? 8 : c == 'u' ? 4 : 2;
                    if (i + count < v.length() - 1) {
                        try {
                            out.append(Character.toChars(Integer.parseInt(v.substring(i + 1, i + count + 1), 16)));
                            i += count;
                            continue;
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int intOf(String v, int def) {
        try { return Integer.parseInt(v.trim()); }
        catch (RuntimeException e) { return def; }
    }
}
