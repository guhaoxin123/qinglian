package io.ghx.vpn.core;

/**
 * 自动更新间隔：统一以分钟为单位，最小 15 分钟。
 * 这里只做算术和文案，不碰 Android API，方便离线测试。
 */
public final class Interval {

    /** 可用的最小间隔（分钟）。 */
    public static final int MIN_MINUTES = 15;

    /** 新订阅默认间隔：12 小时。 */
    public static final int DEFAULT_MINUTES = 12 * 60;

    /** 档位循环：关闭 → 15 分 → 30 分 → 1/3/6/12 小时 → 24 小时 → 关闭。 */
    private static final int[] LADDER = {0, 15, 30, 60, 180, 360, 720, 1440};

    private Interval() {
    }

    /** 负数和 0 都当关闭；其余夹到最小间隔。 */
    public static int normalize(int minutes) {
        if (minutes <= 0) return 0;
        return minutes < MIN_MINUTES ? MIN_MINUTES : minutes;
    }

    /** 点到下一档（含未知值的兜底）。 */
    public static int next(int minutes) {
        int cur = normalize(minutes);
        for (int i = 0; i < LADDER.length; i++) {
            if (LADDER[i] == cur) return LADDER[(i + 1) % LADDER.length];
        }
        for (int i = LADDER.length - 1; i >= 0; i--) {
            if (LADDER[i] < cur) return LADDER[(i + 1) % LADDER.length];
        }
        return LADDER[1];
    }

    /** 间隔毫秒；关闭时返回 0。 */
    public static long periodMs(int minutes) {
        int v = normalize(minutes);
        return v == 0 ? 0L : v * 60_000L;
    }

    /** 距离上次更新是否已经到期。 */
    public static boolean due(long updatedAt, int minutes, long now) {
        long period = periodMs(minutes);
        return period > 0 && now - updatedAt >= period;
    }

    /** 距离下次到期的毫秒数；关闭或已到期返回 0。 */
    public static long remainingMs(long updatedAt, int minutes, long now) {
        long period = periodMs(minutes);
        if (period == 0) return 0L;
        long left = updatedAt + period - now;
        return left <= 0 ? 0L : left;
    }

    /** 卡片上的短标签：不自动 / 15分 / 1h / 24h。 */
    public static String shortZh(int minutes) {
        int v = normalize(minutes);
        if (v == 0) return "不自动";
        if (v % 60 == 0) return (v / 60) + "h";
        return v + "分";
    }

    public static String shortEn(int minutes) {
        int v = normalize(minutes);
        if (v == 0) return "No auto";
        if (v % 60 == 0) return (v / 60) + "h";
        return v + "m";
    }

    /** 设置页的长文案：关闭 / 15 分钟 / 12 小时。 */
    public static String labelZh(int minutes) {
        int v = normalize(minutes);
        if (v == 0) return "关闭";
        if (v < 60) return v + " 分钟";
        if (v % 60 == 0) return (v / 60) + " 小时";
        return (v / 60) + " 小时 " + (v % 60) + " 分";
    }

    public static String labelEn(int minutes) {
        int v = normalize(minutes);
        if (v == 0) return "Off";
        if (v < 60) return v + " min";
        if (v % 60 == 0) return (v / 60) + " h";
        return (v / 60) + " h " + (v % 60) + " min";
    }
}
