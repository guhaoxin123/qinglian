package io.ghx.vpn.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** 订阅下载：跟着跳转、认 gzip、给个像样的 UA。 */
public final class Downloader {

    private static final String UA = "clash-verge/1.7.7 LitLink/1.0 (Android)";
    private static final int TIMEOUT = 20000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public static final class Result {
        public final String body;
        public final String finalUrl;
        public final int status;

        Result(String body, String finalUrl, int status) {
            this.body = body;
            this.finalUrl = finalUrl;
            this.status = status;
        }
    }

    private Downloader() {
    }

    public static Result fetch(String url) throws IOException {
        try {
            return fetchFollowing(url);
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("下载超时（20 秒内没有响应），检查这个地址能否直连", e);
        } catch (java.net.UnknownHostException e) {
            throw new IOException("域名解析失败，检查网络或这个地址", e);
        } catch (javax.net.ssl.SSLException e) {
            throw new IOException("HTTPS 握手失败，可能被拦截或证书有问题", e);
        } catch (java.net.ConnectException e) {
            throw new IOException("连接被拒绝，这个地址可能不可用", e);
        }
    }

    private static Result fetchFollowing(String url) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = open(cur);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.length() == 0) throw new IOException("跳转缺少 Location");
                cur = resolve(cur, loc);
                continue;
            }
            if (code < 200 || code >= 300) {
                String msg = readErr(c);
                c.disconnect();
                throw new IOException("HTTP " + code + (msg.length() > 0 ? (": " + msg) : ""));
            }
            String body = readBody(c);
            c.disconnect();
            return new Result(body, cur, code);
        }
        throw new IOException("重定向次数过多");
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "gzip");
        return c;
    }

    private static String readBody(HttpURLConnection c) throws IOException {
        InputStream in = c.getInputStream();
        String enc = c.getContentEncoding();
        if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("订阅过大（超过 8MB）");
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String readErr(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            if (in == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String s = new String(bos.toByteArray(), "UTF-8").trim();
            if (s.length() > 120) s = s.substring(0, 120);
            return s.replace('\n', ' ');
        } catch (IOException e) {
            return "";
        }
    }

    private static String resolve(String base, String loc) {
        try {
            return new URL(new URL(base), loc).toString();
        } catch (IOException e) {
            return loc;
        }
    }

    /** 订阅里常见的订阅用户信息头。 */
   	public static String userInfo(HttpURLConnection c) {
        String up = c.getHeaderField("subscription-userinfo");
        return up == null ? "" : up;
    }
}
