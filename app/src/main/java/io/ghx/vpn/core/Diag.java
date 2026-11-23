package io.ghx.vpn.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

/** 运行状态诊断：写 <外部files>/diag.log，便于排查（不含任何节点密码）。 */
public final class Diag {

    private Diag() {
    }

    public static void dump(Context ctx, String tag) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(tag).append("] ")
              .append(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                      .format(new java.util.Date())).append('\n');
            Prefs p = new Prefs(ctx);
            sb.append("mode=").append(p.mode())
              .append(" mixed=").append(p.mixedPort())
              .append(" lan=").append(p.allowLan())
              .append(" tun=").append(p.tun()).append('\n');
            sb.append("core=").append(Session.isCoreReady() ? Session.coreVersion() : "not-inited").append('\n');

            SubscriptionManager sm = new SubscriptionManager(ctx);
            List<Profile> profiles = sm.list();
            sb.append("profiles=").append(profiles.size()).append('\n');
            for (int i = 0; i < profiles.size(); i++) {
                Profile pr = profiles.get(i);
                sb.append("  - ").append(pr.name)
                  .append(" type=").append(pr.type)
                  .append(" enabled=").append(pr.enabled)
                  .append(" nodes=").append(pr.nodeEstimate())
                  .append(" auto=").append(pr.autoUpdateMinutes).append("m")
                  .append(" updated=").append(pr.updatedAt)
                  .append(pr.lastError == null ? "" : " err=" + pr.lastError)
                  .append('\n');
            }
            List<io.ghx.vpn.sub.Node> nodes = sm.collectNodes();
            sb.append("nodes=").append(nodes.size()).append('\n');
            for (int i = 0; i < Math.min(nodes.size(), 5); i++) {
                io.ghx.vpn.sub.Node n = nodes.get(i);
                sb.append("  * ").append(n.name).append(" ").append(n.type)
                  .append(" ").append(n.server).append(":").append(n.port).append('\n');
            }
            sb.append("vpnRunning=").append(io.ghx.vpn.svc.TunnelService.isRunning())
              .append(" lastError=").append(io.ghx.vpn.svc.TunnelService.lastError()).append('\n');

            File f = new File(dir, "diag.log");
            FileOutputStream fo = new FileOutputStream(f, true);
            OutputStreamWriter w = new OutputStreamWriter(fo, "UTF-8");
            w.write(sb.toString());
            w.write("\n");
            w.close();
            fo.close();
        } catch (Throwable ignored) {
        }
    }
}
