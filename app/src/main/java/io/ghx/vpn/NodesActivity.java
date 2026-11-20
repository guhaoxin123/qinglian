package io.ghx.vpn;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.HashSet;
import java.util.Set;

import android.content.Context;
import io.ghx.vpn.core.Latency;
import io.ghx.vpn.core.Session;
import io.ghx.vpn.core.SubscriptionManager;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.sub.Regions;
import io.ghx.vpn.ui.Format;
import io.ghx.vpn.ui.Ui;

/** 节点：地区分页 + 双列紧凑列表 + 内核代理 HTTP 健康检查。 */
public class NodesActivity extends Activity {

    private static final String ALL = "__all__";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> testingNames = new HashSet<String>();
    private Future<?> testFuture;
    private Future<?> loadFuture;
    private Latency.Request testRequest;
    private TextView testButton;
    private boolean testing;
    private boolean destroyed;
    private int testGeneration;
    private String testStatus = "";

    private List<Node> all = new ArrayList<Node>();
    private String keyword = "";
    private String region = ALL;

    private LinearLayout tabs;
    private LinearLayout grid;
    private TextView countView;
    private LinearLayout listWrap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        setContentView(build());
        load();
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 24), p, 0);

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
        TextView t = Ui.text(this, "节点", 19f, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testButton = Ui.text(this, "HTTP 测延迟", 13f, Ui.ACCENT);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAll();
            }
        });
        head.addView(testButton);
        root.addView(head);

        countView = Ui.text(this, "加载中…", 11f, Ui.TEXT_DIM);
        countView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        root.addView(countView);

        // 地区分页
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        tabs = Ui.row(this);
        hs.addView(tabs, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(this);
        search.setHint("搜索节点");
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.TEXT_DIM);
        search.setTextSize(13f);
        search.setBackground(Ui.rounded(this, Ui.CARD, 11f));
        search.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 12);
        slp.bottomMargin = Ui.dp(this, 10);
        root.addView(search, slp);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        listWrap = Ui.column(this);
        int bottom = Ui.dp(this, 24);
        listWrap.setPadding(0, 0, 0, bottom);
        sv.addView(listWrap, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = Ui.column(this);
        listWrap.addView(grid);

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

        return root;
    }

    private void load() {
        final Context app = getApplicationContext();
        loadFuture = worker.submit(new Runnable() {
            @Override
            public void run() {
                List<Node> got;
                boolean failed = false;
                try {
                    got = new SubscriptionManager(app).collectNodes();
                } catch (Exception e) {
                    got = new ArrayList<Node>();
                    failed = true;
                }
                if (Thread.currentThread().isInterrupted()) return;
                final List<Node> f = got;
                final boolean loadFailed = failed;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || isFinishing()) return;
                        all = f;
                        if (loadFailed) testStatus = "节点加载失败，请稍后重试";
                        renderTabs();
                        render();
                    }
                });
            }
        });
    }

    @Override
    protected void onStop() {
        cancelTest();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelTest();
        if (loadFuture != null) loadFuture.cancel(true);
        worker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void cancelTest() {
        ++testGeneration;
        if (testRequest != null) testRequest.cancel();
        if (testFuture != null) testFuture.cancel(true);
        testRequest = null;
        testFuture = null;
        if (testing) testStatus = "HTTP 测试已取消";
        testing = false;
        testingNames.clear();
        if (testButton != null) {
            testButton.setEnabled(true);
            testButton.setText("HTTP 测延迟");
        }
        if (!destroyed && grid != null) render();
    }

    /** 统计各地区数量，生成分页。 */
    private void renderTabs() {
        tabs.removeAllViews();
        final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put(ALL, all.size());
        for (int i = 0; i < all.size(); i++) {
            String cc = Regions.codeOf(all.get(i).name, all.get(i).server);
            Integer c = counts.get(cc);
            counts.put(cc, c == null ? 1 : c + 1);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            final String key = e.getKey();
            boolean on = key.equals(region);
            String label = ALL.equals(key) ? "全部 " + e.getValue()
                    : Regions.label(key) + " " + e.getValue();
            TextView chip = Ui.text(this, label, 12f, on ? 0xFFFFFFFF : Ui.TEXT_MID);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
            chip.setBackground(on ? Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_2, 14f)
                    : Ui.stroke(this, Ui.CARD, Ui.LINE, 14f, 1f));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    region = key;
                    renderTabs();
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            tabs.addView(chip, lp);
        }
    }

    private void render() {
        grid.removeAllViews();
        String sel = Session.selected();
        List<Node> shown = new ArrayList<Node>();
        for (int i = 0; i < all.size(); i++) {
            Node n = all.get(i);
            if (!ALL.equals(region)
                    && !region.equals(Regions.codeOf(n.name, n.server))) continue;
            if (keyword.length() > 0
                    && !n.name.toLowerCase(Locale.ROOT).contains(keyword)
                    && !n.server.toLowerCase(Locale.ROOT).contains(keyword)) continue;
            shown.add(n);
        }
        String count = all.isEmpty() ? "还没有节点" : shown.size() + " / " + all.size() + " 个节点";
        countView.setText(testStatus.length() == 0 ? count : count + "\n" + testStatus);
        if (shown.isEmpty()) {
            TextView e = Ui.text(this, all.isEmpty() ? "当前配置没有解析到节点" : "没有匹配的节点",
                    13f, Ui.TEXT_DIM);
            e.setPadding(0, Ui.dp(this, 16), 0, 0);
            grid.addView(e);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.row(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Ui.dp(this, 8);
                grid.addView(row, rlp);
            }
            final Node n = shown.get(i);
            View cell = cell(n, n.name.equals(sel));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 0) clp.rightMargin = Ui.dp(this, 8);
            row.addView(cell, clp);
        }
        if (shown.size() % 2 == 1) {
            View pad = new View(this);
            row.addView(pad, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private View cell(final Node n, boolean selected) {
        LinearLayout c = Ui.column(this);
        c.setPadding(Ui.dp(this, 11), Ui.dp(this, 10), Ui.dp(this, 11), Ui.dp(this, 10));
        c.setBackground(selected
                ? Ui.stroke(this, Ui.CARD_2, Ui.ACCENT, 12f, 1.5f)
                : Ui.stroke(this, Ui.CARD, Ui.LINE, 12f, 1f));

        String cc = Regions.codeOf(n.name, n.server);
        LinearLayout top = Ui.row(this);
        String flag = Regions.flag(cc);
        TextView f = Ui.text(this, flag.length() > 0 ? flag : (cc.length() > 0 ? cc : "•"), 12f, Ui.TEXT);
        f.setPadding(0, 0, Ui.dp(this, 4), 0);
        top.addView(f);

        Integer ms = Latency.get(n.name);
        top.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (testingNames.contains(n.name)) {
            top.addView(Ui.text(this, "测试中…", 10f, Ui.TEXT_DIM));
        } else if (ms != null) {
            String label = ms == Latency.UNAVAILABLE ? "未完成" :
                    (ms == Latency.FAILED ? "HTTP 失败" : Format.latency(ms));
            TextView lat = Ui.text(this, label, 10f, Format.latencyColor(ms));
            top.addView(lat);
        }
        c.addView(top);

        TextView name = Ui.text(this, cleanName(n.name), 12f,
                selected ? Ui.ACCENT : Ui.TEXT);
        name.setMaxLines(2);
        name.setPadding(0, Ui.dp(this, 5), 0, 0);
        c.addView(name);

        TextView meta = Ui.text(this, n.type, 10f, Ui.TEXT_DIM);
        meta.setPadding(0, Ui.dp(this, 4), 0, 0);
        c.addView(meta);

        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = Session.select(NodesActivity.this, n.name);
                if (ok) {
                    Toast.makeText(NodesActivity.this, "已请求切换到 " + n.name,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(NodesActivity.this, "已记住该节点，连接后生效",
                            Toast.LENGTH_SHORT).show();
                }
                render();
            }
        });
        return c;
    }

    /** 名字太长时去掉冗余前缀，保持单行可读。 */
    private static String cleanName(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replace("\uD83C\uDDE8\uD83C\uDDF3", "");
        if (s.length() > 26) s = s.substring(0, 25) + "…";
        return s;
    }

    // ---------------- 测延迟 ----------------

    private void testAll() {
        if (destroyed || testing || all.isEmpty()) return;
        final List<String> target = new ArrayList<String>();
        for (Node n : all) {
            if (ALL.equals(region) || region.equals(Regions.codeOf(n.name, n.server))) {
                if (keyword.length() == 0
                        || n.name.toLowerCase(Locale.ROOT).contains(keyword)
                        || n.server.toLowerCase(Locale.ROOT).contains(keyword)) {
                    target.add(n.name);
                }
            }
        }
        if (target.isEmpty()) return;
        final Context app = getApplicationContext();
        final Latency.Request request = new Latency.Request();
        final int generation = ++testGeneration;
        testRequest = request;
        testing = true;
        testingNames.addAll(target);
        testButton.setEnabled(false);
        testButton.setText("测试中…");
        testStatus = "正在进行代理 HTTP 健康检查（内核测试整组，显示当前筛选结果）";
        render();
        testFuture = worker.submit(new Runnable() {
            @Override
            public void run() {
                final Latency.BatchResult result = Latency.test(app, target, request);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || isFinishing() || generation != testGeneration) return;
                        testing = false;
                        testingNames.clear();
                        testRequest = null;
                        testFuture = null;
                        testButton.setEnabled(true);
                        testButton.setText("HTTP 测延迟");
                        testStatus = result.isCurrent() ? result.message : "配置已变更，测试结果已丢弃";
                        render();
                        Toast.makeText(NodesActivity.this, testStatus, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }
}
