package com.github.kr328.clash.core.bridge;

import android.app.Application;

import kotlinx.coroutines.CompletableDeferred;

/**
 * 内核（mihomo 编译产物 libclash.so）的 JNI 绑定层。
 *
 * 必须保留此包名/类名/方法签名：libbridge.so 导出的符号是
 * Java_com_github_kr328_clash_core_bridge_Bridge_nativeXxx，这些名字本身就是内核 ABI。
 * 除签名对接之外，本工程所有功能（配置生成、订阅解析、UI、服务）都是自研实现。
 */
public final class Bridge {

    public static final Bridge INSTANCE = new Bridge();

    private static volatile Application application;

    private Bridge() {}

    /** 由 Application 在 onCreate 里注入，内核会通过 nativeApplication() 取上下文。 */
    public static void attach(Application app) {
        application = app;
    }

    /** 内核通过 JNI 调用此方法获取 Application（非 native 方法）。 */
    private Application nativeApplication() {
        return application;
    }

    private static volatile boolean loaded;
    private static volatile String loadError;
    private static volatile boolean inited;

    public static synchronized boolean load() {
        if (loaded) return true;
        if (loadError != null) return false;
        try {
            System.loadLibrary("bridge");
            loaded = true;
            return true;
        } catch (Throwable t) {
            loadError = String.valueOf(t);
            return false;
        }
    }

    public static String loadError() {
        return loadError;
    }

    /** 幂等初始化：装载 so 并告知内核 home 目录、版本、SDK。 */
    public static synchronized void setup(java.io.File home, String versionName, int sdk) {
        if (!load()) throw new IllegalStateException("内核装载失败: " + loadError);
        if (inited) return;
        INSTANCE.nativeInit(home.getAbsolutePath(), versionName, sdk);
        inited = true;
    }

    // ---------- native ----------

    private native void nativeInit(String home, String versionName, int sdkVersion);

    public native void nativeReset();

    public native void nativeForceGc();

    public native void nativeSuspend(boolean suspend);

    public native String nativeQueryTunnelState();

    public native long nativeQueryTrafficNow();

    public native long nativeQueryTrafficTotal();

    public native void nativeNotifyDnsChanged(String dnsList);

    public native void nativeNotifyTimeZoneChanged(String name, int offset);

    public native void nativeNotifyInstalledAppChanged(String uidList);

    public native void nativeStartTun(int fd, String stack, String gateway, String portal,
                                      String dns, TunInterface callback);

    public native void nativeStopTun();

    public native String nativeStartHttp(String listenAt);

    public native void nativeStopHttp();

    public native String nativeQueryGroupNames(boolean excludeNotSelectable);

    public native String nativeQueryGroup(String name, String sort);

    public native void nativeHealthCheck(CompletableDeferred deferred, String name);

    public native void nativeHealthCheckAll();

    public native boolean nativePatchSelector(String selector, String name);

    public native void nativeFetchAndValid(FetchCallback callback, String path, String url, boolean force);

    public native void nativeLoad(CompletableDeferred deferred, String path);

    public native String nativeQueryProviders();

    public native void nativeUpdateProvider(CompletableDeferred deferred, String type, String name);

    public native String nativeReadOverride(int slot);

    public native void nativeWriteOverride(int slot, String content);

    public native void nativeClearOverride(int slot);

    public native String nativeQueryConfiguration();

    public native void nativeSubscribeLogcat(LogcatInterface callback);

    public native String nativeCoreVersion();

    public native boolean nativeAttestLocal(byte[] data);

    public native String nativeLocalEngineCapabilities();

    public native String nativeLocalEngineRequest(String method, String payload);

    public native String nativeLocalEngineStart(byte[] key, String config, byte[] seed, boolean force);

    public native String nativeOpenSignedNotice(int slot, byte[] data, int flags);
}
