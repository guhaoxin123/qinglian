package io.ghx.vpn.core;

import android.content.Context;
import android.util.Log;

import com.github.kr328.clash.core.bridge.Bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.ghx.vpn.VpnApp;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.sub.SubParser;

/**
 * 全链路自检（仅在 /sdcard/llb/selftest 存在时运行）。
 * 逐步写入文件，避免进程被系统冻结后日志丢失。
 */
public final class SelfTest {

    private static final String TAG = "LitLinkSelfTest";

    private static File logFile;

    private SelfTest() {
    }

    public static boolean requested(Context ctx) {
        try {
            return new File("/sdcard/llb/selftest").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void step(String msg) {
        String line = "[" + System.currentTimeMillis() % 100000 + "] " + msg;
        Log.i(TAG, line);
        System.out.println(line);
        try {
            if (logFile == null) return;
            FileOutputStream o = new FileOutputStream(logFile, true);
            o.write((line + "\n").getBytes("UTF-8"));
            o.flush();
            o.getFD().sync();
            o.close();
        } catch (Throwable ignored) {
        }
    }

    public static void run(final Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                logFile = new File(dir, "selftest.log");
                if (logFile.exists()) logFile.delete();
            }
        } catch (Throwable ignored) {
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    doRun(ctx);
                } catch (Throwable e) {
                    step("EXC " + e);
                }
            }
        }, "selftest");
        t.start();
    }

    private static void doRun(Context ctx) throws Exception {
        step("start sdk=" + android.os.Build.VERSION.SDK_INT + " abi=" + android.os.Build.SUPPORTED_ABIS[0]);
        Bridge.setup(VpnApp.coreHome(ctx), "1.0.0", android.os.Build.VERSION.SDK_INT);
        step("core=" + Bridge.INSTANCE.nativeCoreVersion());

        // 1. 逐条解析样本
        String[] lines = SAMPLE_LINKS.split("\n");
        int idx = 0, okLinks = 0;
        StringBuilder perLink = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().length() == 0) continue;
            idx++;
            List<Node> ns;
            try {
                ns = SubParser.parseNodes(lines[i], SubParser.FMT_URI);
            } catch (Throwable e) {
                ns = new java.util.ArrayList<Node>();
            }
            perLink.append(idx).append(':').append(ns.isEmpty() ? "x" : ns.get(0).type).append(' ');
            if (!ns.isEmpty()) okLinks++;
        }
        step("parse " + okLinks + "/" + idx + " [" + perLink.toString().trim() + "]");

        // 2. 入库并生成配置
        SubscriptionManager sm = new SubscriptionManager(ctx);
        List<Profile> old = sm.list();
        for (int i = 0; i < old.size(); i++) sm.remove(old.get(i).id);
        Profile sub = sm.addRaw("样本", SAMPLE_LINKS, 0);
        step("nodes=" + sub.nodeEstimate());

        File cfg = sm.writeRuntimeConfig();
        String text = ProfileStore.readFile(cfg);
        step("cfg=" + text.length() + "B proxies=" + countOf(text, "- {name:"));

        File dir = new File(VpnApp.coreHome(ctx), "selftest");
        if (!dir.exists()) dir.mkdirs();
        ProfileStore.writeFile(new File(dir, "config.yaml"), text);
        step("cfgdir=" + dir.getAbsolutePath());

        // 3. 内核装载
        CountDownLatch latch = new CountDownLatch(1);
        String[] err = new String[1];
        Bridge.INSTANCE.nativeLoad(new LoadCallback(latch, err), dir.getAbsolutePath());
        boolean done = latch.await(30, TimeUnit.SECONDS);
        step("load " + (!done ? "TIMEOUT" : (err[0] == null ? "OK" : "ERR " + err[0])));
        if (!done || err[0] != null) return;

        // 4. 内核 JNI 查询（UI 用这个，不依赖 HTTP 控制器）
        step("conf=" + trim(Bridge.INSTANCE.nativeQueryConfiguration(), 220));
        step("groups=" + trim(Bridge.INSTANCE.nativeQueryGroupNames(false), 220));
        step("traffic=" + Bridge.INSTANCE.nativeQueryTrafficNow());

        // 5. 混合端口真实转发测试（直连模式 → 走 DIRECT）
        step("mixed " + ProxyProbe.check("127.0.0.1:7890"));

        // 6. HTTP 控制器（仅外部工具需要）
        String addr = Bridge.INSTANCE.nativeStartHttp("127.0.0.1:0");
        step("http=" + addr);
        step("raw /proxies -> " + rawGet(addr, "/proxies"));
        // 7. 节点切换（UI 依赖）
        step("patch " + Bridge.INSTANCE.nativePatchSelector("节点选择", "A1"));
        step("patch2 " + Bridge.INSTANCE.nativePatchSelector("节点选择", "A2"));
        step("providers=" + trim(Bridge.INSTANCE.nativeQueryProviders(), 120));
        // 7. tun ABI 冒烟
        String tunRes;
        try {
            Bridge.INSTANCE.nativeStartTun(-1, "gvisor", "172.19.0.1",
                    "fdfe:dcba:9876::1", "172.19.0.2", null);
            tunRes = "accepted(-1)";
        } catch (Throwable e) {
            tunRes = e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        Bridge.INSTANCE.nativeStopTun();
        step("tunAbi " + tunRes);
        Bridge.INSTANCE.nativeStopHttp();
        sm.remove(sub.id);
        step("done");
    }

    private static String trim(String s, int max) {
        if (s == null) return "null";
        String t = s.replace('\n', ' ');
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 原始 HTTP/1.1 请求，直接看内核回的字节。 */
    private static String rawGet(String addr, String path) {
        int colon = addr.lastIndexOf(':');
        String host = addr.substring(0, colon);
        int port = Integer.parseInt(addr.substring(colon + 1).trim());
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), 4000);
            s.setSoTimeout(5000);
            String req = "GET " + path + " HTTP/1.0\r\nHost: " + addr + "\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes("UTF-8"));
            s.getOutputStream().flush();
            java.io.InputStream in = s.getInputStream();
            byte[] buf = new byte[700];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, Math.max(n, 0), "UTF-8");
            text = text.replace("\r\n", "\\n");
            return text.length() > 420 ? text.substring(0, 420) + "…" : text;
        } catch (Throwable t) {
            return "EXC " + t.getClass().getSimpleName() + " " + t.getMessage();
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String fetch(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            String body = sb.toString();
            return code + " " + (body.length() > 160 ? body.substring(0, 160) + "…" : body);
        } catch (Throwable t) {
            return "EXC " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }

    private static int countOf(String s, String key) {
        int n = 0, i = 0;
        while ((i = s.indexOf(key, i)) >= 0) {
            n++;
            i += key.length();
        }
        return n;
    }

    /** 样本链接：覆盖 vmess / trojan / ss / socks5，用不存在的服务器，只验证解析与装载。 */
    private static final String SAMPLE_LINKS =
            "vmess://eyJ2IjoiMiIsInBzIjoiQTEiLCJhZGQiOiIxOTIuMC4yLjEiLCJwb3J0IjoiNDQzIiwiaWQiOiIxMTExMTExMS0yMjIyLTMzMzMtNDQ0NC01NTU1NTU1NTU1NTUiLCJhaWQiOiIwIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJhLmV4YW1wbGUuY29tIiwicGF0aCI6Ii93cyIsInRscyI6InRscyIsInNuaSI6ImEuZXhhbXBsZS5jb20iLCJzY3kiOiJhdXRvIn0=\n"
                    + "trojan://pass123@192.0.2.2:443?sni=b.example.com&allowInsecure=1#A2\n"
                    + "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@192.0.2.3:8388#A3\n"
                    + "socks5://user:pa%40ss@192.0.2.4:1080#A4";
}
