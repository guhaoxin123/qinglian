package io.ghx.vpn;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.ghx.vpn.core.LogReader;
import io.ghx.vpn.ui.BaseActivity;
import io.ghx.vpn.ui.L10n;
import io.ghx.vpn.ui.Ui;

public class LogsActivity extends BaseActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView output, refresh, share;
    private LogReader reader;
    private String snapshot = "";
    private boolean busy;
    private String t(String zh, String en) { return L10n.t(this, zh, en); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ui.column(this); body.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this,16); body.setPadding(p,p,p,p);
        TextView back = button(t("‹ 返回", "‹ Back")); back.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { finish(); } }); body.addView(back);
        body.addView(Ui.text(this, t("日志（logcat）", "Logs (logcat)"), 21f, Ui.TEXT));
        body.addView(Ui.text(this, t("只读本应用当前进程的最近日志，非全系统日志。常见链接/密码已尝试隐藏，仍可能有服务器、IP 等信息；分享前请检查。", "Recent logs from this app's current process only, not system-wide logs. Common links/passwords are masked, but servers/IPs may remain. Review before sharing."), 12f, Ui.TEXT_DIM));
        LinearLayout actions = Ui.row(this);
        refresh = button(t("刷新", "Refresh")); refresh.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { load(); } }); actions.addView(refresh);
        share = button(t("分享日志", "Share logs")); share.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View v) { share(); } }); actions.addView(share); body.addView(actions);
        ScrollView scroll = new ScrollView(this);
        output = Ui.text(this,"",12f,Ui.TEXT); output.setTypeface(Typeface.MONOSPACE); output.setTextIsSelectable(true);
        scroll.addView(output); body.addView(scroll, new LinearLayout.LayoutParams(-1,0,1)); setContentView(body); load();
    }
    private TextView button(String label) {
        TextView v = Ui.text(this,label,15f,Ui.ACCENT_TEXT); v.setPadding(0,Ui.dp(this,12),Ui.dp(this,24),Ui.dp(this,12)); return v;
    }
    private void load() {
        if (busy) return; busy=true; refresh.setEnabled(false); share.setEnabled(false);
        if (reader != null) reader.cancel(); reader = new LogReader(); final LogReader task = reader;
        output.setText(t("正在读取本应用日志…", "Reading app logs…"));
        new Thread(new Runnable() { @Override public void run() {
            String result;
            try { result = task.read(android.os.Process.myPid()); }
            catch (Exception e) { result = t("日志读取失败；系统可能限制 logcat。", "Could not read logs; this system may restrict logcat."); }
            final String text = result;
            ui.post(new Runnable() { @Override public void run() { if (isFinishing() || isDestroyed() || reader != task) return;
                snapshot = text; output.setText(text); busy=false; refresh.setEnabled(true); share.setEnabled(true); } });
        } },"app-logcat").start();
    }
    private void share() {
        if (busy || snapshot.length() == 0) return;
        AlertDialog d = new AlertDialog.Builder(this).setTitle(t("确认分享日志", "Share logs?"))
                .setMessage(t("脱敏不保证覆盖所有敏感信息。请先检查日志；只有你在下一步选择目标并发送后才会分享。", "Masking may not cover all sensitive information. Review the logs first; you choose the destination and send them yourself."))
                .setNegativeButton(t("取消", "Cancel"), null).setPositiveButton(t("继续", "Continue"), new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface dialog, int which) {
                    Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,snapshot);
                    try { startActivity(Intent.createChooser(intent, t("分享日志", "Share logs"))); }
                    catch (Exception e) { output.setText(t("没有可用的分享应用", "No sharing app available")); }
                } }).show(); Ui.tintDialog(d);
    }
    @Override protected void onDestroy() {
        if (reader != null) reader.cancel(); ui.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
