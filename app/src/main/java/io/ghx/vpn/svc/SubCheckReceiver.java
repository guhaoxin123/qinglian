package io.ghx.vpn.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.ghx.vpn.core.AutoUpdate;

/** 定时订阅检查：到点了自己醒来刷新订阅，绝不自动连接 VPN。 */
public class SubCheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action != null && !AutoUpdate.ACTION_CHECK.equals(action)) return;
        final PendingResult pr = goAsync();
        AutoUpdate.runIfDue(context, new AutoUpdate.Callback() {
            @Override
            public void onDone(int updated, int failed) {
                pr.finish();
            }
        });
    }
}
