package io.ghx.vpn.core;

import io.ghx.vpn.sub.ClashYaml;

/** Removes only the existing set of rules requiring downloaded geodata/rule providers. */
public final class RuleFilter {
    private RuleFilter() { }

    public static String apply(String yaml) { return apply(yaml, ConfigBuilder.GROUP_NAME); }

    /** The fallback must reference the actual app selector, including a collision suffix. */
    public static String apply(String yaml, String fallback) {
        if (yaml == null || yaml.length() == 0) return yaml;
        StringBuilder out = new StringBuilder(yaml.length());
        boolean inRules = false, hasMatch = false, inRuleProviders = false;
        for (String line : yaml.split("\\r?\\n", -1)) {
            String key = ClashYaml.topKey(line);
            if (key != null) {
                if (inRules && !hasMatch) appendFallback(out, fallback);
                inRules = "rules".equals(key);
                inRuleProviders = "rule-providers".equals(key);
                hasMatch = false;
                if (inRuleProviders) continue;
                if (inRules) {
                    out.append("rules:\n");
                    String tail = ClashYaml.stripComment(line.substring(line.indexOf(':') + 1)).trim();
                    if (tail.startsWith("[")) {
                        for (String rule : ClashYaml.flowList(tail)) {
                            String type = ruleType(rule);
                            if (!needsDownload(type)) {
                                out.append("  - ").append(rule).append('\n');
                                if (isMatch(type)) hasMatch = true;
                            }
                        }
                    }
                    continue;
                }
            }
            if (inRuleProviders) continue;
            String t = line.trim();
            if (inRules && t.startsWith("- ")) {
                String type = ruleType(t.substring(2));
                if (needsDownload(type)) continue;
                if (isMatch(type)) hasMatch = true;
                out.append("  - ").append(t.substring(2)).append('\n');
                continue;
            }
            out.append(line).append('\n');
        }
        if (inRules && !hasMatch) appendFallback(out, fallback);
        return out.toString();
    }

    private static String ruleType(String raw) {
        return ClashYaml.scalar(raw).split(",", 2)[0].trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isMatch(String type) { return type.equals("MATCH") || type.equals("FINAL"); }

    private static void appendFallback(StringBuilder out, String target) {
        out.append("  - MATCH,").append(target).append('\n');
    }

    private static boolean needsDownload(String type) {
        return type.equals("GEOIP") || type.equals("GEOSITE") || type.equals("RULE-SET")
                || type.equals("GEOSITE-NAME") || type.equals("SRC-GEOIP") || type.equals("GEOIP-CODE");
    }
}
