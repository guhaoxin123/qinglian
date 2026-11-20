package io.ghx.vpn.core;

import com.github.kr328.clash.core.bridge.Bridge;
import com.github.kr328.clash.core.bridge.LogcatInterface;
import org.json.JSONObject;

/** Failure telemetry only, never a claim that the browser or upstream node works.
 * Only generic TUN errors are retained, never destinations or configuration secrets.
 */
public final class CoreTunStatus {
    private static boolean installed;
    private static long startedAt;
    private static boolean watching;
    private static String error;

    private CoreTunStatus() {}

    public static synchronized void install() {
        if (installed) return;
        Bridge.INSTANCE.nativeSubscribeLogcat(new LogcatInterface() {
            @Override public void received(String payload) {
                try {
                    JSONObject event = new JSONObject(payload);
                    accept(event.optLong("time", 0), event.optString("level", ""),
                            event.optString("message", ""));
                } catch (Exception ignored) {
                    // A logging callback must never throw into JNI.
                }
            }
        });
        installed = true;
    }

    static synchronized void accept(long time, String level, String message) {
        if (!watching || time < startedAt || !isTunError(level, message)) return;
        error = message.contains("ParsePrefix") ? "内核拒绝 TUN 网段地址"
                : "内核报告 TUN 启动/运行失败";
    }

    public static boolean isTunError(String level, String message) {
        return "error".equalsIgnoreCase(level) && message != null
                && (message.startsWith("TUN:")
                    || message.startsWith("Start TUN listening error:")
                    || message.startsWith("[TUN] Start TUN listening error:"));
    }

    public static synchronized void begin() {
        error = null;
        startedAt = System.currentTimeMillis();
        watching = true;
    }

    public static synchronized String error() { return error; }
    public static synchronized void end() { watching = false; }
}
