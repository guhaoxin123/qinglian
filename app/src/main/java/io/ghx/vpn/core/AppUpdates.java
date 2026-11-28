package io.ghx.vpn.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import io.ghx.vpn.UpdateActivity;
import io.ghx.vpn.ui.L10n;
import io.ghx.vpn.ui.Ui;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public GitHub Releases API only. Never uploads profiles, starts VPN or installs silently. */
public final class AppUpdates {
    private static final AtomicBoolean AUTO_BUSY = new AtomicBoolean();
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("app_updates", Context.MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", true); }
    public static void enabled(Context c, boolean value) { prefs(c).edit().putBoolean("enabled", value).apply(); }
    public static String current(Context c) throws Exception { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
    public static void autoCheck(final Activity activity) {
        final Context app = activity.getApplicationContext();
        long now = System.currentTimeMillis(), last = prefs(app).getLong("attempt", 0);
        if (!enabled(app) || (now >= last && now - last < 86400000L) || !AUTO_BUSY.compareAndSet(false, true)) return;
        prefs(app).edit().putLong("attempt", now).apply();
        new Thread(new Runnable() {
            @Override public void run() {
                Request request = new Request();
                try {
                    final UpdatePolicy.Release release = request.latest();
                    if (UpdatePolicy.compare(release.version, current(app)) <= 0) return;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            if (activity.isFinishing() || activity.isDestroyed() || !enabled(app)) return;
                            AlertDialog dialog = new AlertDialog.Builder(activity)
                                    .setTitle(L10n.t(activity, "发现轻连新版 ", "New version ") + release.version)
                                    .setMessage(L10n.t(activity, "来源：GitHub guhaoxin123/qinglian。需确认后下载与安装，不会自动开启 VPN。", "Source: GitHub guhaoxin123/qinglian. Download and installation require confirmation; the VPN is never started."))
                                    .setNegativeButton(L10n.t(activity, "稍后", "Later"), null)
                                    .setPositiveButton(L10n.t(activity, "查看更新", "View update"), new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface dialog, int which) { activity.startActivity(new Intent(activity, UpdateActivity.class)); } })
                                    .show();
                            Ui.tintDialog(dialog);
                        }
                    });
                } catch (Exception e) { Log.i("应用更新检查未完成（网络或发行版不可用）"); }
                finally { request.cancel(); AUTO_BUSY.set(false); }
            }
        }, "app-update-check").start();
    }
    public interface Progress { void bytes(long done, long total); }
    public static final class Request {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        private volatile InputStream input;
        public void cancel() {
            cancelled = true;
            HttpURLConnection c = connection; if (c != null) c.disconnect();
            InputStream in = input; if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
        private void active(long deadline) throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
            if (System.nanoTime() > deadline) throw new IOException("GitHub 请求超时，请稍后重试");
        }
        private HttpURLConnection open(String url, boolean asset, long deadline) throws Exception {
            for (int redirects = 0; redirects < 6; redirects++) {
                active(deadline);
                if (asset ? !UpdatePolicy.downloadHost(url) : !UpdatePolicy.API.equals(url)) throw new SecurityException("不受信任的更新地址");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                connection = c;
                c.setConnectTimeout(15000); c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "Qinglian-Android-Updater");
                c.setRequestProperty("Accept", asset ? "application/octet-stream" : "application/vnd.github+json");
                c.setRequestProperty("Accept-Encoding", "identity");
                active(deadline);
                int code = c.getResponseCode();
                if (code == 200) return c;
                if (asset && (code == 301 || code == 302 || code == 303 || code == 307 || code == 308)) {
                    String location = c.getHeaderField("Location"); c.disconnect();
                    if (location == null) throw new IOException("更新下载地址无效");
                    url = new URL(new URL(url), location).toExternalForm(); continue;
                }
                c.disconnect();
                if (code == 403 || code == 429) throw new IOException("GitHub 请求受限，请稍后重试");
                if (code == 404) throw new IOException("GitHub 尚无可用正式发行版");
                throw new IOException("GitHub HTTP " + code);
            }
            throw new IOException("更新地址重定向过多");
        }
        public UpdatePolicy.Release latest() throws Exception {
            long deadline = System.nanoTime() + 45000000000L;
            HttpURLConnection c = open(UpdatePolicy.API, false, deadline);
            try {
                input = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    active(deadline);
                    if (out.size() + n > 1024 * 1024) throw new IOException("发行版信息过大");
                    out.write(buffer, 0, n);
                }
                JSONObject root = new JSONObject(out.toString("UTF-8"));
                if (root.optBoolean("draft", true) || root.optBoolean("prerelease", true)) throw new IOException("不是正式发行版");
                String tag = root.getString("tag_name"), version = UpdatePolicy.normalize(tag);
                JSONArray assets = root.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (("qinglian_" + version + ".apk").equals(a.optString("name")) && "uploaded".equals(a.optString("state")))
                        return new UpdatePolicy.Release(tag, a.getString("name"), a.getString("browser_download_url"),
                                a.optString("digest", null), a.getLong("size"), root.optString("body", ""));
                }
                throw new IOException("发行版没有匹配的 APK");
            } finally { if (input != null) input.close(); input = null; c.disconnect(); connection = null; }
        }
        public File download(Context ctx, UpdatePolicy.Release release, Progress progress) throws Exception {
            long deadline = System.nanoTime() + 300000000000L;
            File dir = new File(ctx.getCacheDir(), "updates");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建更新缓存");
            File file = File.createTempFile("qinglian-", ".apk", dir); boolean success = false;
            try {
                HttpURLConnection c = open(release.url, true, deadline);
                try {
                    input = c.getInputStream();
                    MessageDigest hash = MessageDigest.getInstance("SHA-256"); long done = 0, lastReport = 0;
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[32768]; int n;
                        while ((n = input.read(buffer)) != -1) {
                            active(deadline); done += n;
                            if (done > release.size || done > UpdatePolicy.MAX_APK) throw new IOException("安装包超过声明大小");
                            out.write(buffer, 0, n); hash.update(buffer, 0, n);
                            long now = System.nanoTime();
                            if (progress != null && now - lastReport > 250000000L) { progress.bytes(done, release.size); lastReport = now; }
                        }
                        out.getFD().sync();
                    }
                    if (done != release.size || !release.digest.equals(UpdatePolicy.hex(hash.digest()))) throw new SecurityException("安装包 SHA-256 / 大小校验失败");
                    active(deadline); SignatureGuard.verifyUpdate(ctx, file, release);
                    success = true; return file;
                } finally { if (input != null) input.close(); input = null; c.disconnect(); connection = null; }
            } finally { if (!success) file.delete(); } // Only this request's own temporary file.
        }
    }
}
