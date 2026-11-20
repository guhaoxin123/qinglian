package io.ghx.vpn.core;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 打开应用时的订阅自动更新：按各自间隔判断是否到期，后台串行执行。 */
public final class AutoUpdate {

    private static final String TAG = "LitLinkAuto";

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private AutoUpdate() {
    }

    public interface Callback {
        void onDone(int updated, int failed);
    }

    /** Completion is also called when disabled/busy (important for BroadcastReceiver.goAsync). */
    public static void runIfDue(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        if (new Prefs(app).autoUpdateHours() <= 0 || !RUNNING.compareAndSet(false, true)) {
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
                            if (new Prefs(app).autoUpdateHours() <= 0) break;
                            Profile p = due.get(i);
                            // Refresh this snapshot: removed/disabled profiles are not fetched later
                            // merely because they were present when the batch started.
                            Profile current = sm.store().byId(sm.list(), p.id);
                            if (current == null || !current.enabled || !current.hasUrl()
                                    || current.autoUpdateHours <= 0) continue;
                            long period = current.autoUpdateHours * 3600_000L;
                            if (System.currentTimeMillis() - current.updatedAt < period) continue;
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
                        complete(cb, ok, bad);
                    }
                }
            }, "auto-update").start();
        } catch (RuntimeException e) {
            RUNNING.set(false);
            Log.w(TAG, "start auto update failed", e);
            complete(cb, 0, 1);
        }
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
