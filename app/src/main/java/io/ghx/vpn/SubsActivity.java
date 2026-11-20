package io.ghx.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
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
import android.widget.Toast;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

import io.ghx.vpn.core.Prefs;
import io.ghx.vpn.core.Session;
import io.ghx.vpn.core.Profile;
import io.ghx.vpn.core.SubscriptionManager;
import io.ghx.vpn.svc.TunnelService;
import io.ghx.vpn.ui.Ui;

/** 订阅/配置管理：添加、刷新、选用、自动更新、删除。 */
public class SubsActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final Set<String> REFRESHING = Collections.synchronizedSet(new HashSet<String>());

    private SubscriptionManager subs;
    private LinearLayout list;
    private TextView hint;
    private LinearLayout content;
    private ScrollView sv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        subs = new SubscriptionManager(this);
        getWindow().setStatusBarColor(Ui.BG);
        setContentView(build());
        refresh();
        handleImportIntent();
        if (savedInstanceState == null && getIntent().getBooleanExtra("open_add_subscription", false)) {
            addDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refresh();
    }

    private View build() {
        sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        content = Ui.column(this);
        int p = Ui.dp(this, 16);
        content.setPadding(p, Ui.dp(this, 26), p, Ui.dp(this, 30));
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = Ui.row(this);
        TextView back = Ui.text(this, "‹", 22f, Ui.ACCENT);
        back.setPadding(0, 0, Ui.dp(this, 10), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        TextView t = Ui.text(this, "配置", 19f, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(t);
        content.addView(head);

        hint = Ui.text(this, "点卡片选用配置 · 支持 Clash / v2ray / Surfboard",
                12f, Ui.TEXT_DIM);
        hint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 16));
        content.addView(hint);

        list = Ui.column(this);
        content.addView(list);

        LinearLayout btns = Ui.row(this);
        TextView add = fillBtn("＋ 添加订阅", true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addDialog();
            }
        });
        TextView addLocal = fillBtn("导入文件", false);
        addLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                localDialog();
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l1.rightMargin = Ui.dp(this, 8);
        btns.addView(add, l1);
        btns.addView(addLocal, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 18);
        content.addView(btns, blp);
        return sv;
    }

    private TextView fillBtn(String label, boolean primary) {
        TextView t = Ui.text(this, label, 14f, primary ? 0xFFFFFFFF : Ui.TEXT_MID);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        t.setBackground(primary
                ? Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_2, 12f)
                : Ui.stroke(this, Ui.CARD_2, Ui.LINE, 12f, 1f));
        return t;
    }

    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        list.removeAllViews();
        try {
            final List<Profile> all = subs.list();
            if (all.isEmpty()) {
                TextView e = Ui.text(this, "还没有配置，点下方「添加订阅」或导入文件", 13f, Ui.TEXT_DIM);
                e.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
                list.addView(e);
                return;
            }
            Profile act = subs.active();
            for (Profile pr : all) list.addView(card(pr, act != null && pr.id.equals(act.id)));
        } catch (RuntimeException error) {
            list.removeAllViews();
            list.addView(Ui.text(this, "无法读取配置：" + message(error), 13f, Ui.ERR));
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private void showError(String action, Throwable error) {
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, action + "：" + message(error), Toast.LENGTH_LONG).show();
        }
    }

    private View card(final Profile pr, boolean active) {
        LinearLayout c = Ui.card(this);
        if (active) c.setBackground(Ui.stroke(this, Ui.CARD, Ui.ACCENT, 16f, 1.5f));
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                use(pr);
            }
        });

        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, pr.name, 15f, Ui.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (active) {
            TextView on = Ui.text(this, "使用中", 10f, 0xFFFFFFFF);
            on.setBackground(Ui.rounded(this, Ui.ACCENT, 9f));
            on.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.rightMargin = Ui.dp(this, 6);
            top.addView(on, olp);
        }
        top.addView(Ui.text(this, typeLabel(pr), 11f, Ui.ACCENT));
        c.addView(top);

        int count = -1;
        try {
            count = subs.nodesOf(pr).size();
        } catch (Throwable ignored) {
        }
        String info;
        if (pr.lastError != null) {
            info = "错误：" + pr.lastError;
        } else if (pr.updatedAt > 0) {
            info = "更新于 " + ago(pr.updatedAt) + (count > 0 ? " · " + count + " 节点" : "");
        } else {
            info = "尚未更新";
        }
        TextView sub = Ui.text(this, info, 12f,
                pr.lastError != null ? Ui.ERR : Ui.TEXT_DIM);
        sub.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 11));
        c.addView(sub);

        LinearLayout actions = Ui.row(this);
        if (!active) {
            TextView useBtn = action("选用", Ui.ACCENT);
            useBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    use(pr);
                }
            });
            actions.addView(useBtn);
        } else {
            TextView pick = action("节点", Ui.ACCENT);
            pick.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    subs.select(pr.id);
                    startActivity(new Intent(SubsActivity.this, NodesActivity.class));
                }
            });
            actions.addView(pick);
        }

        if (pr.hasUrl()) {
            TextView upd = action("刷新", Ui.TEXT_MID);
            upd.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doRefresh(pr);
                }
            });
            actions.addView(upd);
        }

        TextView auto = action(pr.autoUpdateHours > 0 ? "自动 " + pr.autoUpdateHours + "h" : "不自动",
                Ui.TEXT_MID);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = pr.autoUpdateHours == 0 ? 6 : pr.autoUpdateHours == 6 ? 12
                        : pr.autoUpdateHours == 12 ? 24 : 0;
                try {
                    subs.setAutoUpdateHours(pr.id, next);
                    refresh();
                } catch (RuntimeException error) {
                    showError("保存失败", error);
                }
            }
        });
        actions.addView(auto);

        TextView del = action("删除", Ui.ERR);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SubsActivity.this)
                        .setTitle("删除配置")
                        .setMessage("确定删除「" + pr.name + "」？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                try {
                                    subs.remove(pr.id);
                                    refresh();
                                } catch (RuntimeException error) {
                                    showError("删除失败", error);
                                }
                            }
                        })
                        .show();
            }
        });
        actions.addView(del);

        c.addView(actions);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        c.setLayoutParams(lp);
        return c;
    }

    private static String typeLabel(Profile pr) {
        if (pr.type == Profile.TYPE_CLASH) return "Clash";
        if (pr.type == Profile.TYPE_SURFBOARD) return "Surfboard";
        return "v2ray";
    }

    private TextView action(String label, int color) {
        TextView t = Ui.text(this, label, 12f, color);
        t.setBackground(Ui.rounded(this, Ui.CARD_2, 16f));
        t.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 7);
        t.setLayoutParams(lp);
        return t;
    }

    /** 只重载仍在运行的内核，不用延时 start 覆盖用户的停止操作。 */
    private void use(final Profile pr) {
        try {
            if (subs.store().byId(subs.list(), pr.id) == null) {
                throw new IllegalStateException("配置已删除");
            }
            subs.select(pr.id);
            Toast.makeText(this, "已选用「" + pr.name + "」", Toast.LENGTH_SHORT).show();
            refresh();
            if (!TunnelService.isRunning() && !TunnelService.isStarting()) return;
            final android.content.Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    Session.CORE_LOCK.lock();
                    try {
                        if (!TunnelService.isRunning() || !Session.isKernelRunning()
                                || !pr.id.equals(new Prefs(app).activeProfile())) return;
                        Session.reload(app, new SubscriptionManager(app));
                    } catch (final Exception error) {
                        ui.post(new Runnable() {
                            @Override public void run() { showError("配置已保存，但应用失败", error); }
                        });
                    } finally {
                        Session.CORE_LOCK.unlock();
                    }
                }
            }, "sub-apply").start();
        } catch (RuntimeException error) {
            showError("选用失败", error);
        }
    }

    // ---------------- 添加 ----------------

    private void addDialog() {
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, Ui.dp(this, 6), p, 0);

        final EditText name = new EditText(this);
        name.setHint("名称（可留空）");
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.TEXT_DIM);
        box.addView(name);

        final EditText url = new EditText(this);
        url.setHint("订阅链接 http(s)://…");
        url.setTextColor(Ui.TEXT);
        url.setHintTextColor(Ui.TEXT_DIM);
        url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url);

        new AlertDialog.Builder(this)
                .setTitle("添加订阅")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            String u = url.getText().toString().trim();
                            if (u.length() == 0) return;
                            String scheme = Uri.parse(u).getScheme();
                            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                                throw new IllegalArgumentException("订阅地址需以 http:// 或 https:// 开头");
                            }
                            String n = name.getText().toString().trim();
                            if (n.length() == 0) n = "订阅 " + (subs.list().size() + 1);
                            Profile pr = subs.add(n, u, new Prefs(SubsActivity.this).autoUpdateHours());
                            subs.select(pr.id);
                            refresh();
                            doRefresh(pr);
                        } catch (RuntimeException error) {
                            showError("添加失败", error);
                        }
                    }
                })
                .show();
    }

    private void localDialog() {
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, Ui.dp(this, 6), p, 0);

        final EditText name = new EditText(this);
        name.setHint("名称（可留空）");
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.TEXT_DIM);
        box.addView(name);

        final EditText body = new EditText(this);
        body.setHint("粘贴配置内容：Clash YAML / v2ray 链接（每行一条）");
        body.setTextColor(Ui.TEXT);
        body.setHintTextColor(Ui.TEXT_DIM);
        body.setMinLines(6);
        body.setGravity(Gravity.TOP);
        box.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("粘贴导入")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            String t = body.getText().toString().trim();
                            if (t.length() == 0) return;
                            String n = name.getText().toString().trim();
                            if (n.length() == 0) n = "本地配置 " + (subs.list().size() + 1);
                            Profile pr = subs.addRaw(n, t, 0);
                            subs.select(pr.id);
                            refresh();
                        } catch (RuntimeException error) {
                            showError("导入失败", error);
                        }
                    }
                })
                .show();
    }

    // ---------------- 刷新订阅 ----------------

    private void doRefresh(final Profile pr) {
        if (!REFRESHING.add(pr.id)) {
            Toast.makeText(this, "此订阅正在更新", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在更新…", Toast.LENGTH_SHORT).show();
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    final int[] result = new int[1];
                    final String[] err = new String[1];
                    try {
                        // Do not mutate the Profile captured by the card's UI listeners.
                        Profile current = subs.store().byId(subs.list(), pr.id);
                        if (current == null) throw new IllegalStateException("配置已删除");
                        try {
                            result[0] = subs.refresh(current);
                        } catch (Exception error) {
                            err[0] = message(error);
                            current.lastError = err[0];
                        }
                        subs.update(current);
                    } catch (Exception error) {
                        err[0] = message(error);
                    } finally {
                        REFRESHING.remove(pr.id);
                    }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            refresh();
                            if (err[0] != null) {
                                Toast.makeText(SubsActivity.this, "更新失败：" + err[0],
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(SubsActivity.this, result[0] < 0 ? "已更新（Clash 配置）"
                                        : "已更新 " + result[0] + " 个节点", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }, "sub-refresh").start();
        } catch (RuntimeException error) {
            REFRESHING.remove(pr.id);
            showError("更新失败", error);
        }
    }

    // ---------------- 外部导入 ----------------

    /** 从别的应用「分享」过来的内容 / 链接。 */
    private void handleImportIntent() {
        Intent it = getIntent();
        if (it == null) return;
        String text = it.getStringExtra(Intent.EXTRA_TEXT);
        Uri data = it.getData();
        if (text == null && data != null) text = data.toString();
        if (text == null) return;
        final String t = text.trim();
        if (t.length() == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("导入为配置？")
                .setMessage(t.length() > 200 ? t.substring(0, 200) + "…" : t)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            String scheme = Uri.parse(t).getScheme();
                            boolean url = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
                            Profile pr = url
                                    ? subs.add("导入 " + (subs.list().size() + 1), t,
                                            new Prefs(SubsActivity.this).autoUpdateHours())
                                    : subs.addRaw("导入 " + (subs.list().size() + 1), t, 0);
                            subs.select(pr.id);
                            refresh();
                            if (pr.hasUrl()) doRefresh(pr);
                        } catch (RuntimeException error) {
                            showError("导入失败", error);
                        }
                    }
                })
                .show();
    }

    private static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 60_000) return "刚刚";
        if (d < 3_600_000) return (d / 60_000) + " 分钟前";
        if (d < 86_400_000) return (d / 3_600_000) + " 小时前";
        return (d / 86_400_000) + " 天前";
    }
}
