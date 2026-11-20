package io.ghx.vpn.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

/** DNS 检测：系统解析耗时 + 是否经由代理解析。 */
public final class DnsCheck {

    public long systemMs = -1;      // 系统解析耗时，-1 = 失败
    public long proxyMs = -1;       // 经代理解析耗时（含 TCP 握手），-1 = 失败
    public String resolved;         // 解析到的地址

    public boolean ok() {
        return systemMs >= 0 || proxyMs >= 0;
    }

    /**
     * 系统解析没有超时 API：放进守护线程并限时等待，避免解析卡住拖死整个检测。
     */
    private static String systemResolve(long timeoutMs) {
        final String[] holder = new String[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = InetAddress.getByName("cp.cloudflare.com").getHostAddress();
                } catch (Throwable ignored) {
                }
            }
        }, "dns-check");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    public static DnsCheck run(int proxyPort, int timeoutMs) {
        DnsCheck d = new DnsCheck();

        long t0 = System.currentTimeMillis();
        String resolved = systemResolve(timeoutMs);
        if (resolved != null && resolved.length() > 0) {
            d.resolved = resolved;
            d.systemMs = System.currentTimeMillis() - t0;
        }

        if (proxyPort > 0) {
            long t1 = System.currentTimeMillis();
            Socket s = new Socket();
            try {
                // 用 SOCKS5 让代理端解析域名：先连本机端口，再发 CONNECT
                Proxy p = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", proxyPort));
                s = new Socket(p);
                s.connect(InetSocketAddress.createUnresolved("cp.cloudflare.com", 80), timeoutMs);
                d.proxyMs = System.currentTimeMillis() - t1;
            } catch (Throwable ignored) {
            } finally {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return d;
    }
}
