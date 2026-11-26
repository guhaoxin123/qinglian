package io.ghx.vpn.core;

import android.content.Context;

import com.github.kr328.clash.core.bridge.Bridge;

import java.io.File;
import java.util.Collections;
import java.util.List;

import io.ghx.vpn.VpnApp;
import io.ghx.vpn.sub.Node;

/**
 * 当前运行会话：装载内核、应用配置、切换节点、查询延迟。
 * 进程内单例，UI 与 Service 共用同一份内核实例。
 */
public final class Session {

    private static final Object LOCK = new Object();
    /** 保护“用户选择 vs 装载后写回”的短临界区，避免刚点的节点被覆盖。 */
    private static final Object SELECTION_LOCK = new Object();
    public static final java.util.concurrent.locks.ReentrantLock CORE_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);
    private static volatile boolean inited;
    private static volatile java.util.concurrent.CountDownLatch pendingLoad;
    private static volatile Context appCtx;

    private static volatile List<Node> nodes = Collections.emptyList();
    private static volatile String selected;
    private static volatile String selectionGroup = ConfigBuilder.GROUP_NAME;
    private static volatile String autoGroup = ConfigBuilder.GROUP_AUTO;
    public static String selectionGroup() { return selectionGroup; }
    public static String autoGroup() { return autoGroup; }
    public static void setConfigGroups(String rawBase) {
        selectionGroup = ConfigBuilder.selectionGroupName(rawBase);
        autoGroup = ConfigBuilder.autoGroupName(rawBase);
    }

    private Session() {
    }

    public static void ensure(Context ctx) {
        synchronized (LOCK) {
            appCtx = ctx.getApplicationContext();
            if (inited) return;
            Bridge.attach((android.app.Application) appCtx);
            Bridge.setup(VpnApp.coreHome(appCtx), "1.0.0", android.os.Build.VERSION.SDK_INT);
            try { CoreTunStatus.install(); }
            catch (RuntimeException | LinkageError e) {
                Log.w("TUN 错误监听不可用；不代表内核连接成功", e);
            }
            inited = true;
        }
    }

    public static boolean isCoreReady() {
        return inited;
    }

    /** 调用方须持有 CORE_LOCK：上一次装载/测速未收尾时不再发起新的装载。 */
    private static void requireIdle() {
        if (Latency.hasPendingCheck()) throw new IllegalStateException("上次延迟检测仍未结束，请稍后重试");
        if (pendingLoad != null && pendingLoad.getCount() > 0)
            throw new IllegalStateException("上次配置装载仍未结束，请稍后重试");
    }

    /** 应用一份配置目录（内核从 <dir>/config.yaml 读取）。 */
    public static void apply(Context ctx, File cfgDir) throws Exception {
        CORE_LOCK.lockInterruptibly();
        try {
            requireIdle();
            ensure(ctx);
            Latency.clear();
            prepareStatistics();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            String[] err = new String[1];
            pendingLoad = latch;
            try { Bridge.INSTANCE.nativeLoad(new LoadCallback(latch, err), cfgDir.getAbsolutePath()); }
            catch (UnsatisfiedLinkError e) { latch.countDown(); throw e; }
            if (!latch.await(40, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("配置载入超时");
            }
            if (err[0] != null) throw new IllegalStateException(err[0]);
        } finally {
            CORE_LOCK.unlock();
        }
    }

    /** 重新生成配置并应用。 */
    public static void reload(Context ctx, SubscriptionManager sm) throws Exception {
        CORE_LOCK.lockInterruptibly();
        try {
            // 先确认内核空闲，再动 active/config.yaml：否则会覆盖仍在读取的装载输入。
            requireIdle();
            List<Node> nextNodes = sm.collectNodes();
            File cfg = sm.writeRuntimeConfig();
            File dir = new File(VpnApp.coreHome(ctx), "active");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建配置目录");
            ProfileStore.writeFile(new File(dir, "config.yaml"), ProfileStore.readFile(cfg));
            apply(ctx, dir);
            setConfigGroups(sm.clashBase());
            nodes = Collections.unmodifiableList(new java.util.ArrayList<Node>(nextNodes));
            restoreSelection(ctx);
        } finally {
            CORE_LOCK.unlock();
        }
    }

    /** Called under CORE_LOCK after loading the profile; applies the remembered choice. */
    public static void restoreSelection(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        String name = prefs.selectedNode();
        if (name == null || name.length() == 0) name = autoGroup;
        String applied = name;
        if (!Bridge.INSTANCE.nativePatchSelector(selectionGroup, applied)) {
            applied = autoGroup;
            if (!Bridge.INSTANCE.nativePatchSelector(selectionGroup, applied)) {
                throw new IllegalStateException("无法应用节点选择，请检查配置分组");
            }
        }
        // 用户可能在装载期间刚点了别的节点：在写回偏好前做一次互斥比对，避免把新选择覆盖掉。
        synchronized (SELECTION_LOCK) {
            String latest = prefs.selectedNode();
            if (latest != null && latest.length() > 0 && !latest.equals(applied)) {
                if (Bridge.INSTANCE.nativePatchSelector(selectionGroup, latest)) {
                    applied = latest;
                } else {
                    prefs.selectedNode(applied); // 回退时明确改回实际生效的选择
                }
            } else {
                prefs.selectedNode(applied);
            }
            selected = applied;
        }
    }

    public static List<Node> nodes() {
        return nodes;
    }

    /** 当前选中的节点：内存优先，其次读偏好（未连接时也能记住）。 */
    public static String selected() {
        if (selected != null && selected.length() > 0) return selected;
        if (appCtx != null) {
            String pref = new Prefs(appCtx).selectedNode();
            if (pref != null && pref.length() > 0) return pref;
        }
        return null;
    }

    /**
     * 切换节点。内核没跑时只记住选择，避免在主线程触发 JNI 而卡住界面。
     * 返回值表示是否已即时生效。
     */
    public static boolean select(Context ctx, String nodeName) {
        appCtx = ctx.getApplicationContext();
        final String name = nodeName;
        synchronized (SELECTION_LOCK) {
            selected = name;
            new Prefs(ctx).selectedNode(name);
        }
        if (!isKernelRunning()) return false;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                CORE_LOCK.lock();
                try {
                    if (!isKernelRunning() || !name.equals(selected)) return;
                    if (!Bridge.INSTANCE.nativePatchSelector(selectionGroup, name)) {
                        restoreSelection(app);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                android.widget.Toast.makeText(app, io.ghx.vpn.ui.L10n.t(app, "节点切换失败，已恢复可用选择", "Node switch failed; restored an available selection"), android.widget.Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } catch (Throwable error) {
                    Log.w("节点切换失败", error);
                } finally {
                    CORE_LOCK.unlock();
                }
            }
        }, "select").start();
        return true;
    }

    /** 内核是否已装载配置并在跑（由服务维护）。 */
    private static volatile boolean kernelRunning;

    private static volatile long trafficEpoch;
    public static long trafficEpoch() { return trafficEpoch; }

    public static synchronized void kernelRunning(boolean v) {
        if (kernelRunning != v) trafficEpoch++;
        kernelRunning = v;
        if (!v) Latency.clear();
    }

    private static volatile TrafficStats.Endpoint statisticsEndpoint;

    /** CMFA strips YAML controllers before applying slot 1 (session) overrides.
     * Called only by the existing configuration-load path, never by the dashboard.
     * nativeStartHttp is a forward proxy, NOT the REST controller.
     */
    private static void prepareStatistics() {
        try {
            TrafficStats.Endpoint endpoint = statisticsEndpoint;
            if (endpoint == null) {
                int port;
                try (java.net.ServerSocket reservation = new java.net.ServerSocket(
                        0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                    port = reservation.getLocalPort();
                }
                endpoint = new TrafficStats.Endpoint(port,
                        java.util.UUID.randomUUID().toString().replace("-", ""));
            }
            String old = Bridge.INSTANCE.nativeReadOverride(1);
            org.json.JSONObject json = new org.json.JSONObject(old == null || old.trim().isEmpty() ? "{}" : old);
            java.util.Map<String, Object> previous = new java.util.LinkedHashMap<String, Object>();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) { String key = keys.next(); previous.put(key, json.get(key)); }
            Bridge.INSTANCE.nativeWriteOverride(1, new org.json.JSONObject(endpoint.overrides(previous)).toString());
            statisticsEndpoint = endpoint;
        } catch (Exception | LinkageError error) {
            // Statistics failure must not prevent a user-requested connection.
            statisticsEndpoint = null;
            Log.i("连接数接口准备失败；网速和流量仍使用内核计数器");
        }
    }

    public static TrafficStats.Endpoint statisticsEndpoint() { return statisticsEndpoint; }

    /** Read-only JNI queries under a bounded lock. Never initializes or loads the core. */
    public static TrafficStats.Sample queryTraffic(long epoch) throws Exception {
        if (!CORE_LOCK.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            throw new IllegalStateException("Core busy");
        try {
            if (!kernelRunning || epoch != trafficEpoch) throw new IllegalStateException("Core changed");
            return new TrafficStats.Sample(Bridge.INSTANCE.nativeQueryTrafficNow(),
                    Bridge.INSTANCE.nativeQueryTrafficTotal());
        } finally { CORE_LOCK.unlock(); }
    }

    public static boolean isKernelRunning() {
        return kernelRunning;
    }

    private static String appPref(Context ctx) {
        try {
            return new Prefs(ctx).selectedNode();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 换配置后清掉缓存，让下次 ensure 重新读取。 */
    public static void invalidate() {
        synchronized (LOCK) {
            nodes = Collections.emptyList();
            selected = null;
            Latency.clear();
        }
    }

    public static String coreVersion() {
        if (!inited) return "-";
        try {
            return Bridge.INSTANCE.nativeCoreVersion();
        } catch (Throwable t) {
            return "-";
        }
    }
}
