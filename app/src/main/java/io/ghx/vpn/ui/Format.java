package io.ghx.vpn.ui;

/** 展示用格式化。 */
public final class Format {

    private Format() {
    }

    public static String bytes(long v) {
        if (v < 1024) return v + " B";
        if (v < 1024L * 1024) return String.format("%.1f KB", v / 1024.0);
        if (v < 1024L * 1024 * 1024) return String.format("%.1f MB", v / 1048576.0);
        return String.format("%.2f GB", v / 1073741824.0);
    }

    public static String speed(long bytesPerSec) {
        return bytes(bytesPerSec) + "/s";
    }

    public static String latency(Integer ms) {
        if (ms == null) return "";
        if (ms == -2) return "不可用"; // 未取得可确认的代理检测结果
        if (ms <= 0) return "失败";
        return ms + " ms";
    }

    public static String latency(android.content.Context ctx, Integer ms) {
        if (ms == null) return "";
        if (ms == -2) return L10n.t(ctx, "不可用", "Unavailable");
        if (ms <= 0) return L10n.t(ctx, "失败", "Failed");
        return ms + " ms";
    }

    public static int latencyColor(Integer ms) {
        if (ms == null) return Ui.TEXT_DIM;
        if (ms == -2) return Ui.TEXT_DIM;
        if (ms <= 0) return Ui.ERR;
        if (ms < 200) return Ui.OK;
        if (ms < 600) return Ui.WARN;
        return Ui.ERR;
    }

    public static String ago(android.content.Context ctx, long ts) {
        if (ts <= 0) return L10n.t(ctx, "从未", "Never");
        long d = Math.max(0L, System.currentTimeMillis() - ts);
        if (d < 60_000L) return L10n.t(ctx, "刚刚", "Just now");
        if (d < 3600_000L) return (d / 60_000L) + L10n.t(ctx, " 分钟前", " min ago");
        if (d < 86400_000L) return (d / 3600_000L) + L10n.t(ctx, " 小时前", " h ago");
        long days = d / 86400_000L;
        return days + L10n.t(ctx, " 天前", days == 1 ? " day ago" : " days ago");
    }

    public static String ago(long ts) {
        if (ts <= 0) return "从未";
        long d = System.currentTimeMillis() - ts;
        if (d < 60_000L) return "刚刚";
        if (d < 3600_000L) return (d / 60_000L) + " 分钟前";
        if (d < 86400_000L) return (d / 3600_000L) + " 小时前";
        return (d / 86400_000L) + " 天前";
    }
}
