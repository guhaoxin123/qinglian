import io.ghx.vpn.core.Interval;

/** 自动更新间隔：最小 15 分钟、档位循环、到期判断。纯算术，不碰 Android/VPN。 */
public final class AuditIntervalTest {

    private static int count;

    private static void check(String what, boolean ok) {
        count++;
        if (!ok) throw new AssertionError("FAILED: " + what);
    }

    public static void main(String[] args) {
        check("minimum is 15 minutes", Interval.MIN_MINUTES == 15);
        check("default is 12 h", Interval.DEFAULT_MINUTES == 720);

        check("off stays off", Interval.normalize(0) == 0);
        check("negative is off", Interval.normalize(-30) == 0);
        check("1 min clamps up to 15", Interval.normalize(1) == 15);
        check("14 min clamps up to 15", Interval.normalize(14) == 15);
        check("15 min accepted", Interval.normalize(15) == 15);
        check("720 min accepted", Interval.normalize(720) == 720);

        check("off -> 15 min", Interval.next(0) == 15);
        check("15 -> 30", Interval.next(15) == 30);
        check("30 -> 60", Interval.next(30) == 60);
        check("60 -> 180", Interval.next(60) == 180);
        check("180 -> 360", Interval.next(180) == 360);
        check("360 -> 720", Interval.next(360) == 720);
        check("720 -> 1440", Interval.next(720) == 1440);
        check("1440 -> off", Interval.next(1440) == 0);
        check("unknown clamps then advances", Interval.next(45) == 60);
        check("cycle covers every ladder value", cycleIsStable());

        check("period of off is 0", Interval.periodMs(0) == 0L);
        check("period of 15 min", Interval.periodMs(15) == 15L * 60_000L);
        check("period of 12 h", Interval.periodMs(720) == 12L * 3_600_000L);

        long now = 1_700_000_000_000L;
        check("not due after 14 min at 15 min interval",
                !Interval.due(now - 14 * 60_000L, 15, now));
        check("due after 15 min", Interval.due(now - 15 * 60_000L, 15, now));
        check("off never due", !Interval.due(now - 10L * 3_600_000L, 0, now));
        check("clock skew (updated in future) is not due",
                !Interval.due(now + 3_600_000L, 15, now));

        check("remaining at 15 min interval", Interval.remainingMs(now - 5 * 60_000L, 15, now) == 10 * 60_000L);
        check("remaining is 0 when due", Interval.remainingMs(now - 20 * 60_000L, 15, now) == 0L);
        check("remaining is 0 when off", Interval.remainingMs(now, 0, now) == 0L);

        check("short zh 15", "15分".equals(Interval.shortZh(15)));
        check("short zh 720", "12h".equals(Interval.shortZh(720)));
        check("short zh off", "不自动".equals(Interval.shortZh(0)));
        check("short en 720", "12h".equals(Interval.shortEn(720)));
        check("short en 15", "15m".equals(Interval.shortEn(15)));

        check("label zh 15", "15 分钟".equals(Interval.labelZh(15)));
        check("label zh 60", "1 小时".equals(Interval.labelZh(60)));
        check("label zh 720", "12 小时".equals(Interval.labelZh(720)));
        check("label zh off", "关闭".equals(Interval.labelZh(0)));
        check("label en 30", "30 min".equals(Interval.labelEn(30)));
        check("label en 1440", "24 h".equals(Interval.labelEn(1440)));
        check("label en off", "Off".equals(Interval.labelEn(0)));

        System.out.println("AuditIntervalTest: " + count + " checks passed");
    }

    /** 一路点下去能回到关闭，且最小档就是 15 分钟。 */
    private static boolean cycleIsStable() {
        int v = 0;
        int smallest = Integer.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            v = Interval.next(v);
            if (v == 0) return smallest == Interval.MIN_MINUTES;
            if (v < smallest) smallest = v;
        }
        return false;
    }
}
