package io.ghx.vpn.core;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.HttpURLConnection;

/** 通过内核混合端口发一个真实请求，验证代理链路是否通。 */
public final class ProxyProbe {

    private ProxyProbe() {
    }

    public static String check(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) return "bad addr";
        String host = hostPort.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(hostPort.substring(colon + 1).trim());
        } catch (Throwable t) {
            return "bad port";
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        Throwable last = null;
        for (int i = 0; i < 3; i++) {
            try {
                URL u = new URL("http://www.gstatic.com/generate_204");
                HttpURLConnection c = (HttpURLConnection) u.openConnection(proxy);
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                if (code == 204 || code == 200) {
                    return "OK " + code + " via " + hostPort;
                }
                last = new RuntimeException("code " + code);
            } catch (Throwable t) {
                last = t;
            }
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                break;
            }
        }
        return "FAIL " + (last == null ? "?" : last.getClass().getSimpleName() + " " + last.getMessage());
    }
}
