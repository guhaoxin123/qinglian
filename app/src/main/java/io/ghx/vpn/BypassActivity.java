package io.ghx.vpn;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.ghx.vpn.core.Prefs;
import io.ghx.vpn.ui.BaseActivity;
import io.ghx.vpn.ui.L10n;
import io.ghx.vpn.ui.Ui;

/** 排除 VPN 的应用：选中的应用直接走物理网络，不进隧道。 */
public class BypassActivity extends BaseActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private LinearLayout list;
    private TextView countView;
    private String keyword = "";
    private List<AppItem> apps = new ArrayList<AppItem>();

    private static final class AppItem {
        String pkg;
        String label;
        boolean system;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        setContentView(build());
        loadApps();
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 24), p, Ui.dp(this, 14));

        LinearLayout head = Ui.row(this);
        TextView back = Ui.text(this, L10n.t(this, "‹ 返回", "‹ Back"), 15f, Ui.ACCENT_TEXT);
        back.setPadding(0, 0, Ui.dp(this, 14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        TextView t = Ui.text(this, L10n.t(this, "排除的应用", "Excluded apps"), 18f, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        countView = Ui.text(this, L10n.t(this, "加载中…", "Loading…"), 13f, Ui.ACCENT_TEXT);
        head.addView(countView);
        root.addView(head);

        TextView tip = Ui.text(this, L10n.t(this,
                "选中的应用不走 VPN，直接使用本机网络。改完需重新连接才生效。",
                "Selected apps bypass the VPN and use the device network directly. Reconnect to apply changes."),
                11f, Ui.TEXT_DIM);
        tip.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        root.addView(tip);

        EditText search = new EditText(this);
        search.setHint(L10n.t(this, "搜索应用", "Search apps"));
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.TEXT_DIM);
        search.setTextSize(13f);
        search.setBackground(Ui.rounded(this, Ui.CARD, 10f));
        search.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Ui.dp(this, 10);
        root.addView(search, slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                keyword = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        ScrollView sv = new ScrollView(this);
        list = Ui.column(this);
        sv.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<AppItem> got = new ArrayList<AppItem>();
                try {
                    PackageManager pm = getPackageManager();
                    List<ApplicationInfo> all = pm.getInstalledApplications(0);
                    String self = getPackageName();
                    for (int i = 0; i < all.size(); i++) {
                        ApplicationInfo ai = all.get(i);
                        if (ai.packageName.equals(self)) continue;
                        if (ai.packageName.equals("android")) continue;
                        AppItem it = new AppItem();
                        it.pkg = ai.packageName;
                        it.label = String.valueOf(pm.getApplicationLabel(ai));
                        it.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        got.add(it);
                    }
                    // 已选中的排前面，其次是用户应用，再按名称
                    final Set<String> sel = prefs.bypassApps();
                    Collections.sort(got, new Comparator<AppItem>() {
                        @Override
                        public int compare(AppItem a, AppItem b) {
                            boolean sa = sel.contains(a.pkg);
                            boolean sb = sel.contains(b.pkg);
                            if (sa != sb) return sa ? -1 : 1;
                            if (a.system != b.system) return a.system ? 1 : -1;
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                } catch (Throwable ignored) {
                }
                final List<AppItem> f = got;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        apps = f;
                        render();
                    }
                });
            }
        }, "bypass-load").start();
    }

    private void render() {
        list.removeAllViews();
        if (apps.isEmpty()) {
            countView.setText(L10n.t(this, "读取失败", "Load failed"));
            list.addView(Ui.text(this, L10n.t(this, "读取应用列表失败", "Could not load the app list"), 13f, Ui.TEXT_DIM));
            return;
        }
        Set<String> sel = prefs.bypassApps();
        countView.setText(L10n.t(this, "已选 ", "Selected: ") + sel.size());
        int shown = 0;
        for (int i = 0; i < apps.size(); i++) {
            final AppItem a = apps.get(i);
            if (keyword.length() > 0
                    && !a.label.toLowerCase(Locale.ROOT).contains(keyword)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            shown++;
            if (shown > 200) break;
            list.addView(row(a, sel.contains(a.pkg)));
        }
        if (shown == 0) {
            list.addView(Ui.text(this, L10n.t(this, "没有匹配的应用", "No matching apps"), 13f, Ui.TEXT_DIM));
        }
    }

    private View row(final AppItem a, boolean checked) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
        r.setBackground(Ui.rounded(this, checked ? Ui.CARD_2 : Ui.CARD, 10f));

        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.text(this, a.label, 14f, Ui.TEXT));
        TextView meta = Ui.text(this, a.pkg + (a.system ? L10n.t(this, " · 系统", " · System") : ""), 10f, Ui.TEXT_DIM);
        meta.setPadding(0, Ui.dp(this, 2), 0, 0);
        texts.addView(meta);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView box = Ui.text(this, checked ? L10n.t(this, "✓ 已排除", "✓ Excluded")
                : L10n.t(this, "排除", "Exclude"), 12f, checked ? Ui.ACCENT_TEXT : Ui.TEXT_DIM);
        box.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(box);

        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.toggleBypass(a.pkg);
                render();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 6);
        r.setLayoutParams(lp);
        return r;
    }
}
