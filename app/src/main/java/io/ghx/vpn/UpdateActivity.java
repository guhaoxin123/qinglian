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
import io.ghx.vpn.core.SignatureGuard;
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
    private File apk;
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
        setContentView(scroll); check();
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
        release = null; apk = null; download.setVisibility(View.GONE); install.setVisibility(View.GONE);
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
        setBusy(true); status.setText(t("正在下载…离开此页将取消未完成的下载。", "Downloading… Leaving this page cancels unfinished downloads."));
        new Thread(new Runnable() { @Override public void run() {
            try {
                final File downloaded = task.download(getApplicationContext(), target, new AppUpdates.Progress() { @Override public void bytes(final long done, final long total) { ui.post(new Runnable() { @Override public void run() {
                    if (alive() && request == task) status.setText(t("正在下载：", "Downloading: ") + (done * 100 / total) + "%\n"
                            + t("离开此页将取消。", "Leave this page to cancel."));
                } }); } });
                ui.post(new Runnable() { @Override public void run() { if (!alive() || request != task) return; apk = downloaded; setBusy(false);
                    download.setVisibility(View.GONE); install.setVisibility(View.VISIBLE);
                    status.setText(t("下载完成；SHA-256、包名、版本及原签名校验通过。点击安装后仍需系统确认。", "Downloaded; SHA-256, package, version and original signature verified. Android will ask you to confirm installation."));
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
        try {
            // Re-check immediately before granting the installer access. File stays in private cache.
            SignatureGuard.verifyUpdate(this, apk, release);
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                waitingPermission = true;
                status.setText(t("请允许本应用安装更新，返回后继续系统安装确认。", "Allow this app to install updates, then return for Android's confirmation."));
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); return;
            }
            Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".updates").appendPath(apk.getName()).build();
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Qinglian update", uri));
            startActivity(intent);
        } catch (Exception e) { status.setText(t("无法安装：", "Cannot install: ") + L10n.error(this, e)); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (waitingPermission) { waitingPermission = false;
            if (Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) install();
            else status.setText(t("未允许安装；你可以再次点击安装。", "Installation permission was not granted; tap Install to retry."));
        }
    }
    @Override protected void onDestroy() {
        if (request != null) request.cancel(); ui.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
