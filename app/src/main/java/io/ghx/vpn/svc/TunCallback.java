package io.ghx.vpn.svc;

import android.net.VpnService;

import com.github.kr328.clash.core.bridge.TunInterface;

/**
 * 内核回调：新 socket 需要绑定到 VPN 网卡，避免自己代理自己形成环路。
 * 按 uid 判定是哪个应用发出的连接。
 */
public final class TunCallback implements TunInterface {

    private final VpnService service;

    public TunCallback(VpnService service) {
        this.service = service;
    }

    @Override
    public void markSocket(int fd) {
        try {
            service.protect(fd);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int querySocketUid(int protocol, String source, String target) {
        return -1; // 不按 uid 分流，全部交给内核规则
    }
}
