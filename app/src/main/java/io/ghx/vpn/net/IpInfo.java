package io.ghx.vpn.net;

import org.json.JSONObject;

/** 出口 IP 与归属地检测。 */
public final class IpInfo {

    public String ip;
    public String country;      // 国家名
    public String countryCode;  // ISO 大写，如 JP
    public String city;
    public String isp;
    public boolean viaProxy;
    public long costMs;

    /** 国家旗帜（区域指示符）。不被字体支持时会显示成方块，所以界面上另配文字。 */
    public String flag() {
        return flagOf(countryCode);
    }

    public static String flagOf(String cc) {
        if (cc == null || cc.length() != 2) return "";
        String up = cc.toUpperCase(java.util.Locale.ROOT);
        int a = 0x1F1E6 + (up.charAt(0) - 'A');
        int b = 0x1F1E6 + (up.charAt(1) - 'A');
        if (a < 0x1F1E6 || b < 0x1F1E6) return "";
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    /** proxyPort>0 时经内核出去，得到的是节点出口 IP。 */
    public static IpInfo detect(int proxyPort, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        IpInfo info = null;
        try {
            info = fromIpApi(proxyPort, timeoutMs);
            if (info != null) info.viaProxy = proxyPort > 0;
        } catch (Throwable ignored) {
        }
        if (info == null) {
            try {
                info = fromIpInfoIo(proxyPort, timeoutMs);
                if (info != null) info.viaProxy = proxyPort > 0;
            } catch (Throwable ignored) {
            }
        }
        if (info == null) {
            try {
                info = fromIpSb(proxyPort, timeoutMs);
                if (info != null) info.viaProxy = proxyPort > 0;
            } catch (Throwable ignored) {
            }
        }
        if (info != null) info.costMs = System.currentTimeMillis() - t0;
        return info;
    }

    private static IpInfo fromIpApi(int port, int timeout) throws Exception {
        String body = Net.get("http://ip-api.com/json/?fields=status,country,countryCode,city,isp,query",
                port, timeout);
        JSONObject o = new JSONObject(body);
        if (!"success".equals(o.optString("status"))) return null;
        IpInfo i = new IpInfo();
        i.ip = o.optString("query");
        if (i.ip.length() == 0) return null;
        i.country = o.optString("country");
        i.countryCode = o.optString("countryCode");
        i.city = o.optString("city");
        i.isp = o.optString("isp");
        return i;
    }

    private static IpInfo fromIpInfoIo(int port, int timeout) throws Exception {
        String body = Net.get("https://ipinfo.io/json", port, timeout);
        JSONObject o = new JSONObject(body);
        IpInfo i = new IpInfo();
        i.ip = o.optString("ip");
        if (i.ip.length() == 0) return null; // 限流/错误 JSON 不能当成功
        i.countryCode = o.optString("country");
        i.country = i.countryCode;
        i.city = o.optString("city");
        i.isp = o.optString("org");
        return i;
    }

    private static IpInfo fromIpSb(int port, int timeout) throws Exception {
        String body = Net.get("https://api.ip.sb/geoip", port, timeout);
        JSONObject o = new JSONObject(body);
        IpInfo i = new IpInfo();
        i.ip = o.optString("ip");
        if (i.ip.length() == 0) return null;
        i.countryCode = o.optString("country_code");
        i.country = o.optString("country");
        i.city = o.optString("city");
        i.isp = o.optString("isp");
        return i;
    }
}
