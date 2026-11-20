package io.ghx.vpn.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 轻量偏好设置。默认全局代理。 */
public final class Prefs {

    private static final String FILE = "litlink";
    private static final String K_MODE = "mode";
    private static final String K_MIXED = "mixedPort";
    private static final String K_LAN = "allowLan";
    private static final String K_TUN = "tun";
    private static final String K_AUTO_HOURS = "autoUpdateHours";
    private static final String K_LAST_START = "lastStartAt";
    private static final String K_LAST_SUB = "lastSubCheck";
    private static final String K_BYPASS = "bypassApps";

    private final SharedPreferences sp;

    private final Context appCtx;

    public Prefs(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- 代理模式 ----

    /** 一次性迁移：早期调试可能把模式钉成 direct，这里复位为用户默认的 global。 */
    public void migrateModeOnce() {
        if (sp.getBoolean("modeMigrated", false)) return;
        sp.edit().putString(K_MODE, "global").putBoolean("modeMigrated", true).apply();
    }

    public String mode() {
        // 模式只认用户设置
        return sp.getString(K_MODE, "global");
    }

    public void mode(String v) {
        sp.edit().putString(K_MODE, v).apply();
    }

    // ---- 端口 / 网络 ----

    public int mixedPort() {
        return sp.getInt(K_MIXED, 7890);
    }

    public void mixedPort(int v) {
        sp.edit().putInt(K_MIXED, v).apply();
    }

    public boolean allowLan() {
        return sp.getBoolean(K_LAN, false);
    }

    public void allowLan(boolean v) {
        sp.edit().putBoolean(K_LAN, v).apply();
    }

    public boolean tun() {
        return sp.getBoolean(K_TUN, true);
    }

    public void tun(boolean v) {
        sp.edit().putBoolean(K_TUN, v).apply();
    }

    // ---- 订阅自动更新 ----

    public int autoUpdateHours() {
        return sp.getInt(K_AUTO_HOURS, 12);
    }

    public void autoUpdateHours(int v) {
        sp.edit().putInt(K_AUTO_HOURS, v).apply();
    }

    public long lastSubCheck() {
        return sp.getLong(K_LAST_SUB, 0);
    }

    public void lastSubCheck(long v) {
        sp.edit().putLong(K_LAST_SUB, v).apply();
    }

    /** 当前选用的配置（订阅）id；空 = 自动用第一个启用的。 */
    /** 记住手动选的节点名；连接后回写内核。 */
    public String selectedNode() {
        return sp.getString("selectedNode", "");
    }

    public void selectedNode(String n) {
        sp.edit().putString("selectedNode", n == null ? "" : n).apply();
    }

    public String activeProfile() {
        return sp.getString("activeProfile", "");
    }

    public void activeProfile(String id) {
        sp.edit().putString("activeProfile", id == null ? "" : id).apply();
    }

    public long lastStartAt() {
        return sp.getLong(K_LAST_START, 0);
    }

    public void lastStartAt(long v) {
        sp.edit().putLong(K_LAST_START, v).apply();
    }

    // ---- 排除 VPN 的应用 ----

    /** 不走 VPN 的应用包名集合。 */
    public Set<String> bypassApps() {
        String raw = sp.getString(K_BYPASS, "");
        Set<String> set = new LinkedHashSet<String>();
        if (raw != null && raw.length() > 0) {
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String v = parts[i].trim();
                if (v.length() > 0) set.add(v);
            }
        }
        return set;
    }

    public void bypassApps(Set<String> set) {
        Set<String> cur = new LinkedHashSet<String>();
        if (set != null) cur.addAll(set);
        StringBuilder sb = new StringBuilder();
        for (String v : cur) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        sp.edit().putString(K_BYPASS, sb.toString()).apply();
    }

    public void toggleBypass(String pkg) {
        Set<String> s = bypassApps();
        if (s.contains(pkg)) s.remove(pkg);
        else s.add(pkg);
        bypassApps(s);
    }

    /** 按稳定顺序返回，供 VpnService.Builder 逐个排除。 */
    public List<String> bypassList() {
        return new ArrayList<String>(bypassApps());
    }
}
