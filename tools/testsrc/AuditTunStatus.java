package io.ghx.vpn.core;

/** Synthetic events only; does not call install/JNI, open sockets or start a VPN. */
public final class AuditTunStatus {
    private static int count;
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        count++;
    }
    public static void main(String[] args) {
        check(CoreTunStatus.isTunError("error", "TUN: ParsePrefix missing /"), "prefix error");
        check(CoreTunStatus.isTunError("ERROR", "TUN: configure tun interface failed"), "error casing");
        check(CoreTunStatus.isTunError("error", "Start TUN listening error: x"), "listener error");
        check(CoreTunStatus.isTunError("error", "[TUN] Start TUN listening error: x"), "tagged listener error");
        check(!CoreTunStatus.isTunError("info", "TUN: fd=10"), "info not failure");
        check(!CoreTunStatus.isTunError("error", "[TCP] dial failed"), "node failure not tun init failure");
        check(!CoreTunStatus.isTunError("error", null), "null safe");
        CoreTunStatus.begin();
        CoreTunStatus.accept(0, "error", "TUN: stale");
        check(CoreTunStatus.error() == null, "stale event ignored");
        CoreTunStatus.accept(Long.MAX_VALUE, "info", "TUN: diagnostic");
        check(CoreTunStatus.error() == null, "info ignored");
        CoreTunStatus.accept(Long.MAX_VALUE, "error", "TUN: ParsePrefix secret-host.invalid");
        check("内核拒绝 TUN 网段地址".equals(CoreTunStatus.error()), "classify without retaining raw details");
        CoreTunStatus.begin();
        check(CoreTunStatus.error() == null, "new start clears old error");
        CoreTunStatus.accept(Long.MAX_VALUE, "error", "TUN: generic failure secret-host.invalid");
        check("内核报告 TUN 启动/运行失败".equals(CoreTunStatus.error()), "generic error");
        CoreTunStatus.end();
        CoreTunStatus.accept(Long.MAX_VALUE, "error", "TUN: ParsePrefix");
        check("内核报告 TUN 启动/运行失败".equals(CoreTunStatus.error()), "stopped watcher ignores events");
        CoreTunStatus.begin();
        CoreTunStatus.end();
        CoreTunStatus.accept(Long.MAX_VALUE, "error", "TUN: error");
        check(CoreTunStatus.error() == null, "post-stop event ignored");
        System.out.println("AuditTunStatus: " + count + " checks passed; no JNI/VPN");
    }
}
