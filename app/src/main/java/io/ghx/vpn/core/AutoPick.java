package io.ghx.vpn.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 连接后自动择优。
 *
 * 订阅里的节点常常大半失效，而 Clash 配置默认选中的往往正好是死节点；
 * 另外内核的 GLOBAL 组默认可能指向 DIRECT，会让「全局模式」形同虚设。
 * 这里在连接完成后实测所有节点（用 HTTPS 探针，避免选中能开 HTTP 却打不开 HTTPS 的节点），
 * 把流量切到最快且真的能通的那个。
 */
public final class AutoPick {

    private static final String BASE = "http://127.0.0.1:9090";
    private static final String PROBE = "https://cp.cloudflare.com/generate_204";
    private static final int PROBE_TIMEOUT_MS = 5000;

    private AutoPick() {
    }

    public static void run(String mode) {
        // 控制器可能比 VPN 建立稍晚就绪，重试几次
        for (int i = 0; i < 6; i++) {
            if (attempt(mode)) return;
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
        }
    }

    private static boolean attempt(String mode) {
        if ("direct".equals(mode)) {
            Log.i("自动择优：直连模式，跳过");
            return true;
        }
        try {
            JSONObject proxies = getJson(BASE + "/proxies").optJSONObject("proxies");
            if (proxies == null) {
                Log.i("自动择优：控制器无响应，稍后重试");
                return false;
            }

            List<String> candidates = candidates(proxies);
            Log.i("自动择优：开始实测 " + candidates.size() + " 个节点");
            String best = fastest(proxies, candidates);
            if (best == null) {
                Log.i("自动择优：没有能通的节点");
                return true;
            }
            int d = delay(best);
            Log.i("自动择优：最佳节点 " + best + " (" + d + "ms)");

            String main = findMainGroup(proxies);
            if (main != null && setGroup(main, best)) {
                Log.i("自动择优：" + main + " → " + best);
            }
            // 全局模式：GLOBAL 若还指着 DIRECT，等于没走代理
            String now = groupNow(proxies, "GLOBAL");
            if ("global".equals(mode) && (now == null || "DIRECT".equals(now) || "REJECT".equals(now))) {
                if (setGroup("GLOBAL", best)) Log.i("自动择优：GLOBAL → " + best);
            }
            return true;
        } catch (Throwable t) {
            Log.i("自动择优失败：" + t);
            return false;
        }
    }

    private static List<String> candidates(JSONObject proxies) {
        List<String> out = new ArrayList<String>();
        for (Iterator<String> it = proxies.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject o = proxies.optJSONObject(k);
            if (o == null) continue;
            String type = o.optString("type");
            if ("Selector".equalsIgnoreCase(type) || "URLTest".equalsIgnoreCase(type)
                    || "Fallback".equalsIgnoreCase(type) || "LoadBalance".equalsIgnoreCase(type)) continue;
            if ("DIRECT".equals(k) || "REJECT".equals(k) || "REJECT-DROP".equals(k)
                    || "PASS".equals(k) || "COMPATIBLE".equals(k)) continue;
            out.add(k);
        }
        return out;
    }

    private static String fastest(final JSONObject proxies, List<String> names) {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<int[]>> futures = new ArrayList<Future<int[]>>();
        final List<String> list = names;
        for (int i = 0; i < list.size(); i++) {
            final String name = list.get(i);
            futures.add(pool.submit(new Callable<int[]>() {
                public int[] call() {
                    int d = delay(name);
                    return new int[] {d};
                }
            }));
        }
        String best = null;
        int bestDelay = Integer.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            try {
                int d = futures.get(i).get()[0];
                if (d > 0 && d < bestDelay) {
                    bestDelay = d;
                    best = list.get(i);
                }
            } catch (Throwable ignored) {
            }
        }
        pool.shutdownNow();
        return best;
    }

    private static String findMainGroup(JSONObject proxies) {
        String best = null;
        for (Iterator<String> it = proxies.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject o = proxies.optJSONObject(k);
            if (o == null || !"Selector".equalsIgnoreCase(o.optString("type"))) continue;
            if ("GLOBAL".equals(k)) continue;
            if (best == null || k.contains("节点选择") || k.contains("Proxy")) best = k;
        }
        return best;
    }

    private static String groupNow(JSONObject proxies, String group) {
        JSONObject o = proxies.optJSONObject(group);
        return o == null ? null : o.optString("now", null);
    }

    private static int delay(String name) {
        try {
            String u = BASE + "/proxies/" + URLEncoder.encode(name, "UTF-8")
                    + "/delay?timeout=" + PROBE_TIMEOUT_MS + "&url=" + URLEncoder.encode(PROBE, "UTF-8");
            return getJson(u).optInt("delay", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean setGroup(String group, String name) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    BASE + "/proxies/" + URLEncoder.encode(group, "UTF-8")).openConnection();
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = ("{\"name\":" + JSONObject.quote(name) + "}").getBytes("UTF-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            return c.getResponseCode() / 100 == 2;
        } catch (Throwable t) {
            return false;
        }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(PROBE_TIMEOUT_MS + 4000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new JSONObject(bo.toString("UTF-8"));
        }
    }
}
