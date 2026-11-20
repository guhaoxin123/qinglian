package io.ghx.vpn.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** 轻量 HTTP 客户端：可选走本机代理端口（这样能测到“出口 IP”而不是本机 IP）。 */
public final class Net {

    private Net() {
    }

    /** 通过本机混合端口发请求；port<=0 时直连。HTTP 错误码不算成功。 */
    public static String get(String url, int proxyPort, int timeoutMs) throws IOException {
        HttpURLConnection c = open(url, proxyPort, timeoutMs);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (code < 200 || code >= 300) {
                if (in != null) {
                    try { in.close(); } catch (Throwable ignored) { }
                }
                throw new IOException("HTTP " + code);
            }
            if (in == null) throw new IOException("HTTP " + code);
            return read(in, c.getContentEncoding());
        } finally {
            c.disconnect();
        }
    }

    public static HttpURLConnection open(String url, int proxyPort, int timeoutMs) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c;
        if (proxyPort > 0) {
            Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxyPort));
            c = (HttpURLConnection) u.openConnection(p);
        } else {
            c = (HttpURLConnection) u.openConnection();
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "LitLink/1.0");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "gzip");
        return c;
    }

    public static String read(InputStream raw, String encoding) throws IOException {
        InputStream in = raw;
        if (encoding != null && encoding.toLowerCase().contains("gzip")) {
            in = new GZIPInputStream(raw);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 512 * 1024) break;
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }
}
