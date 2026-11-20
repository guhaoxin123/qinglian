package io.ghx.vpn.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.ghx.vpn.core.AutoUpdate;

/**
 * 开机 / 升级后触发一次订阅检查。
 * 只做订阅刷新，不自动开启 VPN（用户没点连接就不该偷偷接管网络）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        final PendingResult pr = goAsync();
        AutoUpdate.runIfDue(context, new AutoUpdate.Callback() {
            @Override
            public void onDone(int updated, int failed) {
                pr.finish();
            }
        });
    }
}
