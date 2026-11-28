package io.ghx.vpn.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 订阅自动更新：应用打开、开机、以及定时闹钟触发时，按各自间隔判断是否到期，后台串行执行。
 * 最小间隔 15 分钟，因此除了打开应用，还会用闹钟在到期时自己醒来。全程不碰 VPN。
 */
public final class AutoUpdate {

    private static final String TAG = "LitLinkAuto";

    /** 定时检查广播；清单里注册，exported=false。 */
    public static final String ACTION_CHECK = "io.ghx.vpn.action.SUB_CHECK";

    private static final int REQUEST_CODE = 0x5B0C;

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private AutoUpdate() {
    }

    public interface Callback {
        void onDone(int updated, int failed);
    }

    /** 完成时也会回调（关闭/正忙时），方便 BroadcastReceiver.goAsync 结束。 */
    public static void runIfDue(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        if (new Prefs(app).autoUpdateMinutes() <= 0 || !RUNNING.compareAndSet(false, true)) {
            schedule(app);
            complete(cb, 0, 0);
            return;
        }
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int ok = 0, bad = 0;
                    try {
                        SubscriptionManager sm = new SubscriptionManager(app);
                        List<Profile> due = sm.dueForUpdate();
                        for (int i = 0; i < due.size(); i++) {
                            // A user can disable all auto-updates while this batch is in progress.
                            if (new Prefs(app).autoUpdateMinutes() <= 0) break;
                            Profile p = due.get(i);
                            // Refresh this snapshot: removed/disabled profiles are not fetched later
                            // merely because they were present when the batch started.
                            Profile current = sm.store().byId(sm.list(), p.id);
                            if (current == null || !current.enabled || !current.hasUrl()
                                    || Interval.periodMs(current.autoUpdateMinutes) == 0) continue;
                            // 15 分钟档位也要准，所以到期判断统一交给 Interval（分钟为单位）。
                            if (!Interval.due(current.updatedAt, current.autoUpdateMinutes,
                                    System.currentTimeMillis())) continue;
                            p = current;
                            try {
                                sm.refresh(p);
                            } catch (Exception t) {
                                p.lastError = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                            }
                            try {
                                sm.update(p);
                                if (p.lastError == null) {
                                    ok++;
                                    Log.i(TAG, "updated " + p.name);
                                } else {
                                    bad++;
                                }
                            } catch (Exception t) {
                                bad++;
                                Log.w(TAG, "save subscription failed", t);
                            }
                        }
                        new Prefs(app).lastSubCheck(System.currentTimeMillis());
                    } catch (Exception t) {
                        bad++;
                        Log.w(TAG, "auto update failed", t);
                    } finally {
                        RUNNING.set(false);
                        schedule(app);
                        complete(cb, ok, bad);
                    }
                }
            }, "auto-update").start();
        } catch (RuntimeException e) {
            RUNNING.set(false);
            schedule(app);
            Log.w(TAG, "start auto update failed", e);
            complete(cb, 0, 1);
        }
    }

    /** 按最早到期的订阅安排下一次检查；没有需要更新的就取消闹钟。 */
    public static void schedule(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(app);
        long triggerAt = nextDueAt(app);
        try {
            if (triggerAt <= 0) {
                am.cancel(pi);
            } else {
                // setAndAllowWhileIdle 不需要精确闹钟权限，系统粒度约 15 分钟，正合最小间隔。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "schedule failed", e);
        }
    }

    /** 下一次需要检查的时间戳；没有则返回 0。 */
    private static long nextDueAt(Context app) {
        if (new Prefs(app).autoUpdateMinutes() <= 0) return 0L;
        List<Profile> all;
        try {
            all = new SubscriptionManager(app).list();
        } catch (RuntimeException e) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long best = 0L;
        for (int i = 0; i < all.size(); i++) {
            Profile p = all.get(i);
            if (!p.enabled || !p.hasUrl() || Interval.periodMs(p.autoUpdateMinutes) == 0) continue;
            long left = Interval.remainingMs(p.updatedAt, p.autoUpdateMinutes, now);
            long at = left <= 0 ? now + 1000L : now + left;
            if (best == 0L || at < best) best = at;
        }
        return best;
    }

    private static PendingIntent pending(Context app) {
        Intent i = new Intent(app, io.ghx.vpn.svc.SubCheckReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(app, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void complete(Callback cb, int ok, int bad) {
        if (cb == null) return;
        try {
            cb.onDone(ok, bad);
        } catch (RuntimeException e) {
            Log.w(TAG, "auto update callback failed", e);
        }
    }
}
