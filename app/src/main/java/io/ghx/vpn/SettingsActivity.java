package io.ghx.vpn;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.ghx.vpn.core.Prefs;
import io.ghx.vpn.core.Session;
import io.ghx.vpn.svc.TunnelService;
import io.ghx.vpn.ui.Ui;

/** 设置：订阅自动更新、端口、局域网、排除应用、关于。 */
public class SettingsActivity extends Activity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        setContentView(build());
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 24), p, Ui.dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = Ui.row(this);
        TextView back = Ui.text(this, "‹ 返回", 15f, Ui.ACCENT);
        back.setPadding(0, 0, Ui.dp(this, 14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        TextView t = Ui.text(this, "设置", 18f, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t);
        root.addView(head);

        // ---- 订阅 ----
        root.addView(section("订阅"));
        final TextView autoVal = Ui.text(this, autoLabel(), 13f, Ui.ACCENT);
        root.addView(item("新订阅默认更新间隔", "关闭暂停全部自动更新；已有间隔在配置页修改", autoVal,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int cur = prefs.autoUpdateHours();
                        int next = cur == 0 ? 6 : cur == 6 ? 12 : cur == 12 ? 24 : 0;
                        prefs.autoUpdateHours(next);
                        autoVal.setText(autoLabel());
                    }
                }));

        // ---- 代理 ----
        root.addView(section("代理"));
        root.addView(item("混合端口", "HTTP / SOCKS5 共用，范围 1024–65535",
                Ui.text(this, String.valueOf(prefs.mixedPort()), 13f, Ui.ACCENT),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        portDialog();
                    }
                }));

        final TextView lanVal = Ui.text(this, prefs.allowLan() ? "开" : "关", 13f,
                prefs.allowLan() ? Ui.OK : Ui.TEXT_DIM);
        root.addView(item("允许局域网连接", "同网络的设备可用本机代理", lanVal,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.allowLan(!prefs.allowLan());
                        lanVal.setText(prefs.allowLan() ? "开" : "关");
                        lanVal.setTextColor(prefs.allowLan() ? Ui.OK : Ui.TEXT_DIM);
                        applyIfRunning();
                    }
                }));

        // ---- 排除应用 ----
        int bypassCount = prefs.bypassApps().size();
        final TextView bypassVal = Ui.text(this,
                bypassCount == 0 ? "未设置" : bypassCount + " 个", 13f,
                bypassCount == 0 ? Ui.TEXT_DIM : Ui.ACCENT);
        root.addView(item("排除的应用", "选中的应用不走 VPN，直接联网", bypassVal,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, BypassActivity.class));
                    }
                }));

        // ---- 关于 ----
        root.addView(section("关于"));
        root.addView(item("内核版本", "mihomo",
                Ui.text(this, Session.coreVersion(), 12f, Ui.TEXT_DIM), null));
        root.addView(item("应用版本", "LitLink", Ui.text(this, "1.0.0", 12f, Ui.TEXT_DIM), null));

        root.addView(item("捐赠支持", "请作者喝杯咖啡 · 微信赞赏",
                Ui.text(this, "赞赏", 13f, Ui.ACCENT),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        donateDialog();
                    }
                }));

        TextView note = Ui.text(this,
                "支持 Clash / Surfboard / v2ray 订阅；默认全局代理。", 11f, Ui.TEXT_DIM);
        note.setPadding(0, Ui.dp(this, 18), 0, 0);
        root.addView(note);
        return sv;
    }

    private String autoLabel() {
        int h = prefs.autoUpdateHours();
        return h == 0 ? "关闭" : h + " 小时";
    }

    /** 捐赠：赞赏码内置在安装包里（res/drawable-nodpi/donate.png），不联网、不额外占空间。 */
    private void donateDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 14));
        box.setBackground(Ui.stroke(this, Ui.CARD, Ui.LINE, 18f, 1f));

        TextView title = Ui.text(this, "赞赏支持", 16f, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(title);

        TextView sub = Ui.text(this, "如果 LitLink 帮到了你，可以扫码支持一下", 11f, Ui.TEXT_DIM);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 14));
        box.addView(sub);

        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.donate);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 240), ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(img, ilp);

        TextView tip = Ui.text(this, "微信扫一扫 · ghx 的赞赏码", 11f, Ui.TEXT_DIM);
        tip.setGravity(Gravity.CENTER_HORIZONTAL);
        tip.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 14));
        box.addView(tip);

        TextView close = Ui.text(this, "关闭", 14f, Ui.ACCENT);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
        close.setBackground(Ui.stroke(this, Ui.CARD_2, Ui.LINE, 12f, 1f));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        box.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(Ui.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private View section(String title) {
        TextView t = Ui.text(this, title, 12f, Ui.TEXT_DIM);
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 16), 0, Ui.dp(this, 6));
        return t;
    }

    private View item(String title, String sub, TextView right, View.OnClickListener click) {
        LinearLayout c = Ui.card(this);
        LinearLayout row = Ui.row(this);
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.text(this, title, 14f, Ui.TEXT));
        TextView s = Ui.text(this, sub, 11f, Ui.TEXT_DIM);
        s.setPadding(0, Ui.dp(this, 3), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(right);
        c.addView(row);
        if (click != null) c.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        c.setLayoutParams(lp);
        return c;
    }

    private void portDialog() {
        final EditText e = new EditText(this);
        e.setText(String.valueOf(prefs.mixedPort()));
        e.setTextColor(Ui.TEXT);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new android.app.AlertDialog.Builder(this)
                .setTitle("混合端口")
                .setView(e)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int v = Integer.parseInt(e.getText().toString().trim());
                            if (v < 1024 || v > 65535) {
                                Toast.makeText(SettingsActivity.this, "端口需在 1024-65535",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            prefs.mixedPort(v);
                            setContentView(build());
                            applyIfRunning();
                        } catch (RuntimeException t) {
                            Toast.makeText(SettingsActivity.this, "端口无效", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    /** 重载现有内核；用户停止后绝不通过延时 start 重新开启 VPN。 */
    private void applyIfRunning() {
        if (!TunnelService.isRunning() && !TunnelService.isStarting()) return;
        final android.content.Context app = getApplicationContext();
        Toast.makeText(this, "正在应用新设置…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                Session.CORE_LOCK.lock();
                try {
                    if (!TunnelService.isRunning() || !Session.isKernelRunning()) return;
                    Session.reload(app, new io.ghx.vpn.core.SubscriptionManager(app));
                } catch (final Exception error) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            String detail = error.getMessage() == null ? error.toString() : error.getMessage();
                            Toast.makeText(app, "设置已保存，但应用失败：" + detail, Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    Session.CORE_LOCK.unlock();
                }
            }
        }, "settings-apply").start();
    }
}
