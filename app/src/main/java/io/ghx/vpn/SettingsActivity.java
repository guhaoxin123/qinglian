package io.ghx.vpn;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.ghx.vpn.core.Prefs;
import io.ghx.vpn.core.Session;
import io.ghx.vpn.svc.TunnelService;
import io.ghx.vpn.ui.Appearance;
import io.ghx.vpn.ui.BaseActivity;
import io.ghx.vpn.ui.ColorMath;
import io.ghx.vpn.ui.ColorPickerDialog;
import io.ghx.vpn.ui.L10n;
import io.ghx.vpn.ui.Ui;

/** Appearance preferences are separate from proxy preferences and never reload the tunnel. */
public class SettingsActivity extends BaseActivity {
    private Prefs prefs;
    private ScrollView scroller;
    private TextView bypassValue;
    private ColorPickerDialog colorPicker;

    private String t(String zh, String en) { return L10n.t(this, zh, en); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(build());
        if (savedInstanceState != null) {
            final int scroll = savedInstanceState.getInt("settings_scroll", 0);
            scroller.post(new Runnable() {
                @Override public void run() { scroller.scrollTo(0, scroll); }
            });
            Bundle draft = savedInstanceState.getBundle("palette_draft");
            if (draft != null) showPalette(draft);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (isAppearanceRecreating()) return;
        updateBypassValue();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (scroller != null) outState.putInt("settings_scroll", scroller.getScrollY());
        if (colorPicker != null && colorPicker.isShowing()) {
            outState.putBundle("palette_draft", colorPicker.saveDraft());
        }
        super.onSaveInstanceState(outState);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (colorPicker != null && colorPicker.isShowing()) colorPicker.resize();
    }

    @Override protected void onDestroy() {
        if (colorPicker != null) colorPicker.dismiss();
        super.onDestroy();
    }

    private View build() {
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 28));
        scroller.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = Ui.row(this);
        TextView back = Ui.text(this, t("‹ 返回", "‹ Back"), 15f, Ui.ACCENT_TEXT);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinHeight(Ui.dp(this, 48));
        back.setMinWidth(Ui.dp(this, 64));
        back.setPadding(0, 0, Ui.dp(this, 14), 0);
        back.setContentDescription(t("返回", "Back"));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);
        TextView title = Ui.text(this, t("设置", "Settings"), 21f, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        root.addView(head);

        root.addView(section(t("外观", "Appearance")));
        root.addView(item(t("语言", "Language"), t("仅更改应用界面语言", "Change the app language only"),
                value(languageLabel()), new View.OnClickListener() {
                    @Override public void onClick(View v) { languageDialog(); }
                }));
        root.addView(item(t("主题", "Theme"), t("跟随系统或始终使用浅色 / 深色", "System, light, or dark appearance"),
                value(themeLabel()), new View.OnClickListener() {
                    @Override public void onClick(View v) { themeDialog(); }
                }));

        final Switch dynamic = new Switch(this);
        dynamic.setContentDescription(t("使用壁纸配色", "Use wallpaper colors"));
        dynamic.setMinHeight(Ui.dp(this, 48));
        dynamic.setChecked(Appearance.isDynamic(this));
        dynamic.setEnabled(Appearance.supportsDynamic());
        Ui.tintControl(dynamic);
        dynamic.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                Appearance.setDynamic(SettingsActivity.this, checked);
                recreateForAppearance();
            }
        });
        root.addView(item(t("Material You 壁纸配色", "Material You wallpaper colors"),
                Appearance.supportsDynamic()
                        ? t("根据系统壁纸自动选择强调色", "Use the system wallpaper's accent color")
                        : t("需要 Android 12 或更高版本；可使用自定义配色", "Requires Android 12 or later; custom colors are available"),
                dynamic, null));

        LinearLayout swatchRow = Ui.row(this);
        View swatch = new View(this);
        swatch.setBackground(Ui.stroke(this, Ui.ACCENT, Ui.TEXT_DIM, 16f, 1.5f));
        swatch.setContentDescription(t("当前强调色 ", "Current accent ") + ColorMath.hex(Ui.ACCENT));
        swatchRow.addView(swatch, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView swatchHex = value(ColorMath.hex(Ui.ACCENT));
        swatchHex.setPadding(Ui.dp(this, 8), 0, 0, 0);
        swatchRow.addView(swatchHex);
        root.addView(item(t("自定义配色", "Custom palette"),
                Appearance.isDynamic(this)
                        ? t("确认自定义颜色将关闭壁纸配色", "Confirming a custom color turns wallpaper colors off")
                        : t("色相 / 饱和度、亮度、RGB 与 Hex", "Hue / saturation, brightness, RGB and Hex"),
                swatchRow, new View.OnClickListener() {
                    @Override public void onClick(View v) { showPalette(null); }
                }));
        TextView preview = Ui.text(this, t("配色预览 · 轻连", "Palette preview · 轻连"), 14f, Ui.ON_ACCENT);
        preview.setTextColor(Ui.ON_ACCENT);
        preview.setGravity(Gravity.CENTER);
        preview.setMinHeight(Ui.dp(this, 48));
        preview.setBackground(Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_2, 24f));
        root.addView(preview);

        root.addView(section(t("订阅", "Subscriptions")));
        final TextView autoValue = value(autoLabel());
        root.addView(item(t("新订阅默认更新间隔", "Default interval for new subscriptions"),
                t("关闭暂停全部自动更新；已有间隔在配置页修改", "Off pauses all automatic updates; edit existing intervals in Profiles"),
                autoValue, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int current = prefs.autoUpdateHours();
                        int next = current == 0 ? 6 : current == 6 ? 12 : current == 12 ? 24 : 0;
                        prefs.autoUpdateHours(next);
                        autoValue.setText(autoLabel());
                    }
                }));

        root.addView(section(t("代理", "Proxy")));
        root.addView(item(t("混合端口", "Mixed port"),
                t("HTTP / SOCKS5 共用，范围 1024–65535", "Shared HTTP / SOCKS5 port, range 1024–65535"),
                value(String.valueOf(prefs.mixedPort())), new View.OnClickListener() {
                    @Override public void onClick(View v) { portDialog(); }
                }));
        final TextView lanValue = value(onOff(prefs.allowLan()));
        lanValue.setTextColor(prefs.allowLan() ? Ui.OK : Ui.TEXT_DIM);
        root.addView(item(t("允许局域网连接", "Allow LAN connections"),
                t("同网络的设备可用本机代理", "Devices on the same network can use this proxy"),
                lanValue, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.allowLan(!prefs.allowLan());
                        lanValue.setText(onOff(prefs.allowLan()));
                        lanValue.setTextColor(prefs.allowLan() ? Ui.OK : Ui.TEXT_DIM);
                        applyIfRunning();
                    }
                }));
        bypassValue = value("");
        updateBypassValue();
        root.addView(item(t("排除的应用", "Excluded apps"),
                t("选中的应用不走 VPN，直接联网", "Selected apps connect directly, without the VPN"),
                bypassValue, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, BypassActivity.class));
                    }
                }));

        root.addView(section(t("关于", "About")));
        root.addView(item(t("内核版本", "Core version"), "mihomo", value(Session.coreVersion()), null));
        root.addView(item(t("应用版本", "App version"), "轻连", value(appVersion()), null));
        root.addView(item(t("捐赠支持", "Support development"),
                t("请作者喝杯咖啡 · 微信赞赏", "Buy the author a coffee · WeChat donation"),
                value(t("赞赏", "Donate")), new View.OnClickListener() {
                    @Override public void onClick(View v) { donateDialog(); }
                }));
        TextView note = Ui.text(this,
                t("支持 Clash / Surfboard / v2ray 订阅；默认全局代理。",
                        "Supports Clash / Surfboard / v2ray subscriptions; global proxy is the default."),
                12f, Ui.TEXT_DIM);
        note.setPadding(0, Ui.dp(this, 18), 0, 0);
        root.addView(note);
        return scroller;
    }

    private void updateBypassValue() {
        if (prefs == null || bypassValue == null) return;
        int count = prefs.bypassApps().size();
        bypassValue.setText(count == 0 ? t("未设置", "None") : count + t(" 个", " apps"));
        bypassValue.setTextColor(count == 0 ? Ui.TEXT_DIM : Ui.ACCENT_TEXT);
    }

    private TextView value(String text) {
        TextView result = Ui.text(this, text, 13f, Ui.ACCENT_TEXT);
        result.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        return result;
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return t("未知", "Unknown"); }
    }

    private String onOff(boolean on) { return on ? t("开", "On") : t("关", "Off"); }
    private String autoLabel() {
        int hours = prefs.autoUpdateHours();
        return hours == 0 ? t("关闭", "Off") : hours + t(" 小时", " hours");
    }

    private String languageLabel() {
        String mode = Appearance.language(this);
        return "en".equals(mode) ? "English" : "zh".equals(mode) ? "中文" : t("跟随系统", "System");
    }

    private String themeLabel() {
        String mode = Appearance.theme(this);
        return "light".equals(mode) ? t("浅色", "Light") : "dark".equals(mode)
                ? t("深色", "Dark") : t("跟随系统", "System");
    }

    private void languageDialog() {
        final String[] values = {"system", "en", "zh"};
        String[] labels = {t("跟随系统", "Follow system"), "English", "中文"};
        int selected = "en".equals(Appearance.language(this)) ? 1
                : "zh".equals(Appearance.language(this)) ? 2 : 0;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("语言", "Language"))
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        Appearance.setLanguage(SettingsActivity.this, values[which]);
                        recreateForAppearance();
                    }
                }).setNegativeButton(t("取消", "Cancel"), null).show();
        Ui.tintDialog(dialog);
    }

    private void themeDialog() {
        final String[] values = {"system", "light", "dark"};
        String[] labels = {t("跟随系统", "Follow system"), t("浅色", "Light"), t("深色", "Dark")};
        int selected = "light".equals(Appearance.theme(this)) ? 1
                : "dark".equals(Appearance.theme(this)) ? 2 : 0;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("主题", "Theme"))
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        Appearance.setTheme(SettingsActivity.this, values[which]);
                        recreateForAppearance();
                    }
                }).setNegativeButton(t("取消", "Cancel"), null).show();
        Ui.tintDialog(dialog);
    }

    private void showPalette(Bundle draft) {
        if (colorPicker != null && colorPicker.isShowing()) return;
        colorPicker = new ColorPickerDialog(this, Ui.ACCENT, new ColorPickerDialog.OnConfirmListener() {
            @Override public void onConfirm(int color) {
                // The only palette persistence path; also disables wallpaper colors atomically.
                Appearance.setCustomAccent(SettingsActivity.this, color);
                recreateForAppearance();
            }
        });
        if (draft != null) colorPicker.restoreDraft(draft);
        colorPicker.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) { colorPicker = null; }
        });
        colorPicker.show();
    }

    /** Donation asset remains bundled and works offline. */
    private void donateDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 14));
        box.setBackground(Ui.stroke(this, Ui.CARD, Ui.LINE, 24f, 1f));
        scroll.addView(box);
        TextView title = Ui.text(this, t("赞赏支持", "Support 轻连"), 18f, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(title);
        TextView sub = Ui.text(this, t("如果 轻连 帮到了你，可以扫码支持一下",
                "If 轻连 helps you, scan the code to support its development"), 12f, Ui.TEXT_DIM);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        box.addView(sub);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.donate);
        image.setContentDescription(t("微信赞赏二维码", "WeChat donation QR code"));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(image, imageParams);
        TextView tip = Ui.text(this, t("微信扫一扫 · ghx 的赞赏码", "Scan with WeChat · ghx's donation code"),
                12f, Ui.TEXT_DIM);
        tip.setGravity(Gravity.CENTER_HORIZONTAL);
        tip.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 14));
        box.addView(tip);
        TextView close = Ui.chip(this, t("关闭", "Close"), false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        box.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            int width = Math.min(Ui.dp(this, 340), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32));
            int height = Math.min(Ui.dp(this, 560), getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 64));
            window.setLayout(Math.max(1, width), Math.max(1, height));
        }
    }

    private View section(String title) {
        TextView view = Ui.text(this, title, 13f, Ui.TEXT_DIM);
        view.setPadding(Ui.dp(this, 2), Ui.dp(this, 24), 0, Ui.dp(this, 8));
        return view;
    }

    private View item(String title, String subtitle, View right, View.OnClickListener click) {
        LinearLayout card = Ui.card(this);
        LinearLayout row = Ui.row(this);
        LinearLayout texts = Ui.column(this);
        texts.setPadding(0, 0, Ui.dp(this, 12), 0);
        texts.addView(Ui.text(this, title, 15f, Ui.TEXT));
        TextView sub = Ui.text(this, subtitle, 12f, Ui.TEXT_DIM);
        sub.setPadding(0, Ui.dp(this, 5), 0, 0);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Long translations and large font sizes remain scrollable instead of squeezing the title.
        if (getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale > 1.2f) {
            row.removeView(texts);
            texts.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(texts);
            if (right instanceof TextView) ((TextView) right).setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            right.setPadding(right.getPaddingLeft(), Ui.dp(this, 8), right.getPaddingRight(), 0);
            card.addView(right);
        } else {
            row.addView(right);
            card.addView(row);
        }
        if (click != null) {
            card.setOnClickListener(click);
            card.setFocusable(true);
            // Let accessibility aggregate live child labels/values instead of a stale snapshot.
        }
        card.setMinimumHeight(Ui.dp(this, 64));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(params);
        return card;
    }

    private void portDialog() {
        final EditText edit = new EditText(this);
        edit.setText(String.valueOf(prefs.mixedPort()));
        edit.setSelectAllOnFocus(true);
        edit.setSingleLine(true);
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edit.setContentDescription(t("混合端口", "Mixed port"));
        edit.setHint("1024–65535");
        Ui.tintControl(edit);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
        box.addView(edit);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("混合端口", "Mixed port")).setView(box)
                .setNegativeButton(t("取消", "Cancel"), null)
                .setPositiveButton(t("保存", "Save"), null).create();
        dialog.show();
        Ui.tintDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    int port = Integer.parseInt(edit.getText().toString().trim());
                    if (port < 1024 || port > 65535) {
                        edit.setError(t("端口需在 1024–65535", "Port must be between 1024 and 65535"));
                        return;
                    }
                    prefs.mixedPort(port);
                    dialog.dismiss();
                    final int y = scroller.getScrollY();
                    setContentView(build());
                    scroller.post(new Runnable() {
                        @Override public void run() { scroller.scrollTo(0, y); }
                    });
                    applyIfRunning();
                } catch (NumberFormatException error) {
                    edit.setError(t("端口无效", "Invalid port"));
                }
            }
        });
    }

    /** Existing proxy settings may reload an already-running core, never start a stopped VPN. */
    private void applyIfRunning() {
        if (!TunnelService.isRunning() && !TunnelService.isStarting()) return;
        final android.content.Context app = getApplicationContext();
        Toast.makeText(this, t("正在应用新设置…", "Applying settings…"), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                Session.CORE_LOCK.lock();
                try {
                    if (!TunnelService.isRunning() || !Session.isKernelRunning()) return;
                    Session.reload(app, new io.ghx.vpn.core.SubscriptionManager(app));
                } catch (final Exception error) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(app, L10n.t(app, "设置已保存，但应用失败：",
                                    "Settings saved, but could not be applied: ") + L10n.error(app, error),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } finally { Session.CORE_LOCK.unlock(); }
            }
        }, "settings-apply").start();
    }
}
