package io.ghx.vpn;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.ghx.vpn.core.AppUpdates;
import io.ghx.vpn.core.UpdateCache;
import io.ghx.vpn.core.UpdatePolicy;
import io.ghx.vpn.ui.BaseActivity;
import io.ghx.vpn.ui.L10n;
import io.ghx.vpn.ui.Ui;
import java.io.File;

/** Downloads only after confirmation; installation is always Android's user-confirmed UI. */
public class UpdateActivity extends BaseActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, download, install, retry;
    private AppUpdates.Request request;
    private UpdatePolicy.Release release;
    private File apk, heldApk;
    private String pendingApk;
    private boolean busy, waitingPermission;
    private String t(String zh, String en) { return L10n.t(this, zh, en); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(Ui.BG);
        LinearLayout body = Ui.column(this); int p = Ui.dp(this, 18); body.setPadding(p,p,p,p); scroll.addView(body);
        button(body, t("‹ 返回", "‹ Back"), new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { finish(); } });
        body.addView(Ui.text(this, t("应用更新", "App updates"), 22f, Ui.TEXT));
        body.addView(Ui.text(this, "GitHub · guhaoxin123/qinglian", 13f, Ui.TEXT_DIM));
        status = Ui.text(this, "", 14f, Ui.TEXT); status.setTextIsSelectable(true); status.setPadding(0,p,0,p); body.addView(status);
        retry = button(body, t("检查更新", "Check for updates"), new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { check(); } });
        download = button(body, t("下载更新", "Download update"), new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { confirmDownload(); } }); download.setVisibility(View.GONE);
        install = button(body, t("安装已验证的更新", "Install verified update"), new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { install(); } }); install.setVisibility(View.GONE);
        button(body, t("打开 GitHub 发行版", "Open GitHub releases"), new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdatePolicy.REPO + "/releases/latest"))); }
            catch (Exception e) { status.setText(t("没有可打开链接的浏览器", "No browser available")); }
        } });
        setContentView(scroll);
        if (!restore(state)) check();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (release != null && pendingApk != null) {
            state.putString("update.file", pendingApk); // Basename only, never an arbitrary path/URI.
            state.putString("update.version", release.version); state.putString("update.url", release.url);
            state.putString("update.digest", release.digest); state.putLong("update.size", release.size);
            state.putString("update.notes", release.notes); state.putBoolean("update.permission", waitingPermission);
        }
        super.onSaveInstanceState(state);
    }
    private void forgetApk() {
        if (heldApk != null) UpdateCache.release(heldApk);
        heldApk = null; apk = null; pendingApk = null;
    }
    private void holdApk(String name) throws Exception {
        if (heldApk != null && heldApk.getName().equals(name)) return;
        forgetApk(); heldApk = UpdateCache.retain(UpdateCache.directory(getCacheDir()), name); pendingApk = name;
    }
    private boolean restore(Bundle state) {
        if (state == null || state.getString("update.file") == null) return false;
        try {
            release = UpdateCache.restoreRelease(state.getString("update.version"), state.getString("update.url"),
                    state.getString("update.digest"), state.getLong("update.size"), state.getString("update.notes"));
            holdApk(state.getString("update.file"));
            waitingPermission = state.getBoolean("update.permission", false);
            verifyCached(false); return true;
        } catch (Exception e) { forgetApk(); release = null; waitingPermission = false; return false; }
    }
    private void showReady() {
        setBusy(false); download.setVisibility(View.GONE); install.setVisibility(View.VISIBLE);
        status.setText(t("安装包 SHA-256、大小、包名、版本及原签名校验通过。请点击安装，仍需系统确认。",
                "APK SHA-256, size, package, version and original signature verified. Tap Install; Android still asks for confirmation."));
    }
    /** Hash/signature verification runs off the main thread; restoration never opens the installer. */
    private void verifyCached(final boolean userRequestedInstall) {
        if (release == null || pendingApk == null || busy) return;
        if (request != null) request.cancel();
        request = new AppUpdates.Request(); final AppUpdates.Request task = request;
        final UpdatePolicy.Release target = release; final String name = pendingApk;
        setBusy(true); status.setText(t("正在重新验证已下载的安装包…", "Revalidating the downloaded APK…"));
        new Thread(new Runnable() { @Override public void run() {
            try {
                final File verified = task.cached(getApplicationContext(), name, target);
                ui.post(new Runnable() { @Override public void run() {
                    if (!alive() || request != task) return;
                    apk = verified; showReady();
                    if (userRequestedInstall) launchInstaller();
                } });
            } catch (final Exception e) {
                ui.post(new Runnable() { @Override public void run() {
                    if (!alive() || request != task) return;
                    forgetApk(); waitingPermission = false; setBusy(false);
                    install.setVisibility(View.GONE); download.setVisibility(View.VISIBLE);
                    status.setText(t("缓存校验失败；请重新检查或下载：", "Cached APK verification failed; check or download again: ")
                            + L10n.error(UpdateActivity.this, e));
                } });
            } finally { task.cancel(); }
        } }, "app-update-verify").start();
    }
    private TextView button(LinearLayout body, String label, View.OnClickListener listener) {
        TextView v = Ui.text(this, label, 15f, Ui.ACCENT_TEXT); v.setMinHeight(Ui.dp(this, 52));
        v.setGravity(android.view.Gravity.CENTER_VERTICAL); v.setOnClickListener(listener); body.addView(v); return v;
    }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void setBusy(boolean value) { busy = value; retry.setEnabled(!value); download.setEnabled(!value); install.setEnabled(!value); }
    private void check() {
        if (busy) return;
        if (request != null) request.cancel();
        request = new AppUpdates.Request(); final AppUpdates.Request task = request;
        forgetApk(); release = null; waitingPermission = false;
        download.setVisibility(View.GONE); install.setVisibility(View.GONE);
        setBusy(true); status.setText(t("正在从 GitHub 检查正式发行版…", "Checking GitHub for a stable release…"));
        new Thread(new Runnable() { @Override public void run() {
            try {
                final UpdatePolicy.Release found = task.latest(); final String current = AppUpdates.current(UpdateActivity.this);
                final boolean newer = UpdatePolicy.compare(found.version, current) > 0;
                ui.post(new Runnable() { @Override public void run() { if (!alive() || request != task) return; setBusy(false); release = found;
                    status.setText(t("当前版本：", "Current: ") + current + "\n" + t("最新版本：", "Latest: ") + found.version
                            + "\n\n" + (newer ? t("可下载更新", "Update available") : t("当前已是最新版本（或本地版本更高）", "Up to date (or local version is newer)"))
                            + "\n\n" + found.notes);
                    download.setVisibility(newer ? View.VISIBLE : View.GONE);
                } });
            } catch (Exception e) { error(task, e); }
            finally { task.cancel(); }
        } }, "manual-update-check").start();
    }
    private void confirmDownload() {
        if (busy || release == null) return;
        AlertDialog d = new AlertDialog.Builder(this).setTitle(t("下载新版", "Download update"))
                .setMessage(t("下载约 ", "Download about ") + ((release.size + 1048575) / 1048576) + " MiB\n"
                        + t("来源为指定 GitHub 仓库。下载完成后检查 SHA-256、包名、版本和原签名，再由你确认安装。", "From the specified GitHub repository. Verify SHA-256, package, version and original signature before you confirm installation."))
                .setNegativeButton(t("取消", "Cancel"), null).setPositiveButton(t("下载", "Download"), new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface dialog, int which) { download(); } }).show();
        Ui.tintDialog(d);
    }
    private void download() {
        if (busy || release == null) return;
        if (request != null) request.cancel();
        request = new AppUpdates.Request(); final AppUpdates.Request task = request; final UpdatePolicy.Release target = release;
        // The worker's reservation is the sole download lease; UI retains only after successful verification.
        forgetApk(); pendingApk = UpdateCache.basename(target);
        setBusy(true); status.setText(t("正在下载…离开此页将取消未完成的下载。", "Downloading… Leaving this page cancels unfinished downloads."));
        new Thread(new Runnable() { @Override public void run() {
            try {
                final File downloaded = task.download(getApplicationContext(), target, new AppUpdates.Progress() { @Override public void bytes(final long done, final long total) { ui.post(new Runnable() { @Override public void run() {
                    if (alive() && request == task) status.setText(t("正在下载：", "Downloading: ") + (done * 100 / total) + "%\n"
                            + t("离开此页将取消。", "Leave this page to cancel."));
                } }); } });
                ui.post(new Runnable() { @Override public void run() {
                    if (!alive() || request != task) return;
                    try { holdApk(downloaded.getName()); apk = downloaded; showReady(); }
                    catch (Exception e) { error(task, e); }
                } });
            } catch (Exception e) { error(task, e); }
            finally { task.cancel(); }
        } }, "app-update-download").start();
    }
    private void error(AppUpdates.Request task, Exception error) {
        ui.post(new Runnable() { @Override public void run() { if (!alive() || request != task) return; setBusy(false);
            status.setText(t("更新失败：", "Update failed: ") + L10n.error(UpdateActivity.this, error)); } });
    }
    private void install() {
        if (busy || apk == null || release == null) return;
        verifyCached(true); // Only this explicit tap may eventually open Android's confirmation UI.
    }
    private void launchInstaller() {
        if (!alive() || isAppearanceRecreating() || apk == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                waitingPermission = true;
                status.setText(t("请允许本应用安装更新，返回后再次点击安装。", "Allow this app to install updates, then return and tap Install again."));
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); return;
            }
            UpdateCache.installing(apk);
            Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".updates").appendPath(apk.getName()).build();
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Qinglian update", uri));
            startActivity(intent);
        } catch (Exception e) { waitingPermission = false; status.setText(t("无法安装：", "Cannot install: ") + L10n.error(this, e)); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (isAppearanceRecreating()) return;
        if (waitingPermission) { waitingPermission = false;
            if (!busy) status.setText(Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()
                    ? t("安装权限已允许；请再次点击安装。", "Installation permission granted; tap Install again.")
                    : t("未允许安装；你可以再次点击安装。", "Installation permission was not granted; tap Install to retry."));
        }
    }
    @Override protected void onDestroy() {
        if (request != null) request.cancel(); ui.removeCallbacksAndMessages(null); forgetApk(); super.onDestroy();
    }
}
