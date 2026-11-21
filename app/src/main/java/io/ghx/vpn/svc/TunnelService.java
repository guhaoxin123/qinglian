package io.ghx.vpn.svc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import com.github.kr328.clash.core.bridge.Bridge;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.ghx.vpn.VpnApp;
import io.ghx.vpn.MainActivity;
import io.ghx.vpn.core.Diag;
import io.ghx.vpn.core.LoadCallback;
import io.ghx.vpn.core.Log;
import io.ghx.vpn.core.Prefs;
import io.ghx.vpn.core.Session;
import io.ghx.vpn.core.ProfileStore;
import io.ghx.vpn.core.SubscriptionManager;
import io.ghx.vpn.ui.L10n;

/**
 * 隧道服务：建立 VpnService 并把 fd 交给内核，内核用 tun 接管流量。
 */
public class TunnelService extends VpnService implements Runnable {

    public static final String ACTION_START = "io.ghx.vpn.START";
    public static final String ACTION_STOP = "io.ghx.vpn.STOP";

    private static final String CH_ID = "tunnel";
    private static final int NOTIFY_ID = 1001;
    private static final String TUN_ADDR4 = "172.19.0.1";
    private static final int TUN_PREFIX4 = 30;
    // The bundled core calls netip.ParsePrefix: a bare IP silently fails across the void JNI API.
    private static final String TUN_GATEWAY4 = TUN_ADDR4 + "/" + TUN_PREFIX4;
    private static final String TUN_DNS = "172.19.0.2";
    private static final int MTU = 9000;
    /** 内核装载上限：超过就快速失败，不留用户干等。 */
    private static final long LOAD_TIMEOUT_MS = 40000L;

    private static volatile boolean running = false;
    private static volatile String lastError = null;

    private static volatile boolean starting;
    private static TunnelService owner; // protected by Session.CORE_LOCK
    private volatile boolean stopRequested;
    private volatile Thread worker;
    private ParcelFileDescriptor tunFd;

    public static boolean isStarting() { return starting; }

    public static boolean isRunning() {
        return running;
    }

    public static String lastError() {
        return lastError;
    }

    public static void start(android.content.Context ctx) {
        Intent i = new Intent(ctx, TunnelService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(android.content.Context ctx) {
        Intent i = new Intent(ctx, TunnelService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        startForeground(NOTIFY_ID, buildNotification(L10n.t(this, "正在启动…", "Starting…")));
        lastError = null;
        stopRequested = false;
        starting = true;
        worker = new Thread(this, "tunnel");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void run() {
        boolean locked = false;
        try {
            long t0 = System.currentTimeMillis();
            Log.i("隧道线程启动");
            Session.CORE_LOCK.lockInterruptibly();
            locked = true;
            if (stopRequested) throw new InterruptedException("已取消");
            if (owner != null && owner != this) throw new IllegalStateException("上一连接正在停止，请稍后重试");
            owner = this;
            SubscriptionManager sm = new SubscriptionManager(this);
            int nodeCount = sm.collectNodes().size();
            if (nodeCount == 0 && sm.clashBase() == null) {
                throw new IllegalStateException("还没有节点，请先添加或选用一个配置");
            }
            Session.reload(this, sm);
            Log.i("内核配置载入成功 (" + (System.currentTimeMillis() - t0) + "ms)");
            if (stopRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消");

            // 3. 建立 VPN 接口
            Log.i("正在建立 VPN 接口…");
            Builder b = new Builder();
            b.setSession("轻连");
            b.setMtu(MTU);
            b.addAddress(TUN_ADDR4, TUN_PREFIX4);
            b.addDnsServer(TUN_DNS);
            b.addRoute("0.0.0.0", 0);
            b.setBlocking(false);
            // ConfigBuilder disables IPv6. Do not install a v6 route the native stack cannot serve.
            // No allowFamily(AF_INET6): Android blocks the unsupported family instead of leaking it.
            try {
                b.addDisallowedApplication(getPackageName());
            } catch (Throwable t) {
                Log.w("排除自身应用失败", t);
            }
            java.util.List<String> bypass = new Prefs(this).bypassList();
            for (int i = 0; i < bypass.size(); i++) {
                try {
                    b.addDisallowedApplication(bypass.get(i));
                } catch (Throwable t) {
                    Log.w("排除应用失败 " + bypass.get(i), t);
                }
            }
            Log.i("已排除应用 " + (bypass.size() + 1) + " 个");

            tunFd = b.establish();
            if (tunFd == null) {
                throw new IllegalStateException("系统未授予 VPN 权限，请重新连接并在弹窗点「允许」");
            }
            Log.i("VPN 接口已建立 fd=" + tunFd.getFd());

            // 4. Native owns this fd (the reference service also uses detachFd).
            // Never close it again in Java after nativeStopTun: that risks a recycled-fd double close.
            io.ghx.vpn.core.CoreTunStatus.begin();
            TunCallback callback = new TunCallback(this);
            Log.i("TUN 启动参数 IPv4=" + TUN_GATEWAY4 + " DNS=" + TUN_DNS);
            int nativeFd = tunFd.detachFd();
            tunFd = null;
            Bridge.INSTANCE.nativeStartTun(
                    nativeFd, "gvisor", TUN_GATEWAY4, TUN_DNS, TUN_DNS, callback);

            // nativeLoad already opens ConfigBuilder's mixed-port (HTTP + SOCKS).
            // A second HTTP-only listener on the same port fails; null means failure, never 'ok'.
            Log.i("本地检测使用配置的 mixed-port=" + new Prefs(this).mixedPort());
            String tunError = io.ghx.vpn.core.CoreTunStatus.error();
            if (tunError != null) throw new IllegalStateException(tunError);
            if (stopRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException("已取消");
            running = true;
            starting = false;
            Session.kernelRunning(true);
            Session.CORE_LOCK.unlock();
            locked = false;
            lastError = null;
            new Prefs(this).lastStartAt(System.currentTimeMillis());
            Log.i("VPN 接口已交给内核；尚未验证浏览器联网，耗时=" + (System.currentTimeMillis() - t0) + "ms");
            notify(L10n.t(this, "VPN 接口已建立 · ", "VPN interface ready · ")
                    + nodeCount + L10n.t(this, " 个节点", " nodes"));
            Diag.dump(this, "connected");

            while (running) {
                String error = io.ghx.vpn.core.CoreTunStatus.error();
                if (error != null) throw new IllegalStateException(error);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (Throwable t) {
            if (!stopRequested && !(t instanceof InterruptedException)) {
                String msg = t.getMessage();
                lastError = msg == null ? String.valueOf(t) : msg;
                Log.e("连接失败: " + lastError, t);
                notify(L10n.t(this, "连接失败：", "Connection failed: ") + L10n.message(this, lastError));
            }
        } finally {
            if (!locked) Session.CORE_LOCK.lock();
            try {
                if (owner == this) {
                    cleanup();
                    owner = null;
                }
                if (owner == null) { running = false; starting = false; }
            } finally { Session.CORE_LOCK.unlock(); }
            // 失败或断开后立即收尾，不留一个空转的 VPN 服务
            stopSelf();
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
        }
    }
    private void stopTunnel() {
        stopRequested = true;
        io.ghx.vpn.core.Latency.cancelActive();
        Thread w = worker;
        if (w != null) w.interrupt();
        // JNI 清理由原工作线程在 CORE_LOCK 下执行，不在 UI 线程阻塞或与启动竞态。
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
    }

    private void cleanup() {
        io.ghx.vpn.core.CoreTunStatus.end();
        Session.kernelRunning(false);
        Log.i("正在清理 VPN 接口" );
        try {
            Bridge.INSTANCE.nativeStopHttp();
        } catch (Throwable ignored) {
        }
        try {
            Bridge.INSTANCE.nativeStopTun();
        } catch (Throwable ignored) {
        }
        ParcelFileDescriptor fd = tunFd;
        tunFd = null;
        if (fd != null) {
            try {
                fd.close();
            } catch (Throwable ignored) {
            }
        }
        running = false;
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID,
                L10n.t(this, "连接状态", "Connection status"), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_ID);
        else b = new Notification.Builder(this);
        b.setContentTitle("轻连")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void notify(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFY_ID, buildNotification(text));
            } catch (Throwable ignored) {
            }
        }
    }
}
