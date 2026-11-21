package io.ghx.vpn.sub;

import java.util.Locale;

/** 按名称/地址猜节点地区，给出两字母代码与旗帜。 */
public final class Regions {

    private static final String[][] TABLE = {
            {"HK", "香港", "hong kong", "hongkong", "hk"},
            {"SG", "新加坡", "singapore", "狮城"},
            {"JP", "日本", "japan", "东京", "大阪", "tokyo", "osaka"},
            {"US", "美国", "united states", "america", "洛杉矶", "圣何塞", "西雅图", "纽约"},
            {"TW", "台湾", "台湾省", "taiwan", "taipei", "台北"},
            {"KR", "韩国", "korea", "首尔", "seoul"},
            {"GB", "英国", "united kingdom", "london", "伦敦"},
            {"DE", "德国", "germany", "法兰克福", "frankfurt"},
            {"FR", "法国", "france", "paris", "巴黎"},
            {"RU", "俄罗斯", "russia", "moscow"},
            {"CA", "加拿大", "canada", "toronto"},
            {"AU", "澳大利亚", "australia", "sydney"},
            {"IN", "印度", "india", "mumbai"},
            {"TR", "土耳其", "turkey", "istanbul"},
            {"NL", "荷兰", "netherlands", "amsterdam"},
            {"MY", "马来西亚", "malaysia"},
            {"TH", "泰国", "thailand", "bangkok"},
            {"VN", "越南", "vietnam"},
            {"PH", "菲律宾", "philippines"},
            {"AE", "阿联酋", "dubai", "emirates"},
    };

    private Regions() {
    }

    /** 返回两字母代码；识别不出返回 ""。 */
    public static String codeOf(String name, String server) {
        String hay = ((name == null ? "" : name) + " " + (server == null ? "" : server))
                .toLowerCase(Locale.ROOT);
        for (int i = 0; i < TABLE.length; i++) {
            String cc = TABLE[i][0];
            for (int k = 1; k < TABLE[i].length; k++) {
                String kw = TABLE[i][k];
                if (kw.length() <= 2) {
                    // 短词要求独立出现，避免 "hk" 命中随机字符串
                    if (containsWord(hay, kw)) return cc;
                } else if (hay.contains(kw)) {
                    return cc;
                }
            }
        }
        return "";
    }

    private static boolean containsWord(String hay, String w) {
        int from = 0;
        while (true) {
            int i = hay.indexOf(w, from);
            if (i < 0) return false;
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
            int end = i + w.length();
            boolean rightOk = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    /** 旗帜；字体不支持时返回空串，由界面回退成代码文字。 */
    public static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "";
        String up = cc.toUpperCase(Locale.ROOT);
        int a = 0x1F1E6 + (up.charAt(0) - 'A');
        int b = 0x1F1E6 + (up.charAt(1) - 'A');
        if (up.charAt(0) < 'A' || up.charAt(0) > 'Z') return "";
        if (up.charAt(1) < 'A' || up.charAt(1) > 'Z') return "";
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    public static String label(String cc, boolean english) {
        if (!english) return label(cc);
        if (cc == null || cc.length() == 0) return "Other";
        String name = new Locale("", cc.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH);
        return name.length() == 0 ? cc : name;
    }

    public static String label(String cc) {
        if (cc == null || cc.length() == 0) return "其他";
        for (int i = 0; i < TABLE.length; i++) {
            if (TABLE[i][0].equals(cc)) return TABLE[i][1];
        }
        return cc;
    }
}
