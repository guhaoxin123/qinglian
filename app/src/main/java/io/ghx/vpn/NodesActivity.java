package io.ghx.vpn;

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
import io.ghx.vpn.ui.BaseActivity;
import io.ghx.vpn.ui.Format;
import io.ghx.vpn.ui.L10n;
import io.ghx.vpn.ui.Ui;

/** 节点：地区分页 + 双列紧凑列表 + 内核代理 HTTP 健康检查。 */
public class NodesActivity extends BaseActivity {

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
    /** 当前配置是否只有一个地址、还没有内容（订阅没下载成功）。 */
    private boolean pendingDownload;
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
        TextView back = Ui.text(this, "‹", 22f, Ui.ACCENT_TEXT);
        back.setContentDescription(L10n.t(this, "返回", "Back"));
        back.setPadding(0, 0, Ui.dp(this, 10), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        TextView t = Ui.text(this, L10n.t(this, "节点", "Nodes"), 19f, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testButton = Ui.text(this, L10n.t(this, "HTTP 测延迟", "HTTP latency"), 13f, Ui.ACCENT_TEXT);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAll();
            }
        });
        head.addView(testButton);
        root.addView(head);

        countView = Ui.text(this, L10n.t(this, "加载中…", "Loading…"), 11f, Ui.TEXT_DIM);
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
        search.setHint(L10n.t(this, "搜索节点", "Search nodes"));
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
                boolean pending = false;
                try {
                    SubscriptionManager sm = new SubscriptionManager(app);
                    got = sm.collectNodes();
                    io.ghx.vpn.core.Profile act = sm.active();
                    // 只有地址、没有内容：这条订阅还没下载成功，空列表不是解析失败。
                    pending = act != null && act.hasUrl()
                            && (act.content == null || act.content.length() == 0);
                } catch (Exception e) {
                    got = new ArrayList<Node>();
                    failed = true;
                }
                if (Thread.currentThread().isInterrupted()) return;
                final List<Node> f = got;
                final boolean loadFailed = failed;
                final boolean pendingDownloadNow = pending;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || isFinishing()) return;
                        all = f;
                        pendingDownload = pendingDownloadNow;
                        if (loadFailed) testStatus = L10n.t(NodesActivity.this,
                                "节点加载失败，请稍后重试", "Could not load nodes. Try again later.");
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
        if (testing) testStatus = L10n.t(this, "HTTP 测试已取消", "HTTP test cancelled");
        testing = false;
        testingNames.clear();
        if (testButton != null) {
            testButton.setEnabled(true);
            testButton.setText(L10n.t(this, "HTTP 测延迟", "HTTP latency"));
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
            String label = ALL.equals(key) ? L10n.t(this, "全部 ", "All ") + e.getValue()
                    : Regions.label(key, L10n.isEnglish(this)) + " " + e.getValue();
            TextView chip = Ui.text(this, label, 12f, on ? Ui.ON_ACCENT : Ui.TEXT_MID);
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
        String count = all.isEmpty() ? L10n.t(this, "还没有节点", "No nodes yet")
                : String.format(Locale.US, L10n.t(this, "%1$d / %2$d 个节点", "Nodes: %1$d / %2$d"),
                        shown.size(), all.size());
        countView.setText(testStatus.length() == 0 ? count : count + "\n" + testStatus);
        if (shown.isEmpty()) {
            String empty = all.isEmpty()
                    ? (pendingDownload
                        ? L10n.t(this, "这个订阅还没下载成功，回到配置页点「刷新」", "This subscription has not downloaded yet; tap Refresh on the profile")
                        : L10n.t(this, "当前配置没有解析到节点", "No nodes found in the current configuration"))
                    : L10n.t(this, "没有匹配的节点", "No matching nodes");
            TextView e = Ui.text(this, empty, 13f, Ui.TEXT_DIM);
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
            top.addView(Ui.text(this, L10n.t(this, "测试中…", "Testing…"), 10f, Ui.TEXT_DIM));
        } else if (ms != null) {
            String label = ms == Latency.UNAVAILABLE ? L10n.t(this, "未完成", "Incomplete") :
                    (ms == Latency.FAILED ? L10n.t(this, "HTTP 失败", "HTTP failed") : Format.latency(this, ms));
            TextView lat = Ui.text(this, label, 10f, Format.latencyColor(ms));
            top.addView(lat);
        }
        c.addView(top);

        TextView name = Ui.text(this, cleanName(n.name), 12f,
                selected ? Ui.ACCENT_TEXT : Ui.TEXT);
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
                    Toast.makeText(NodesActivity.this,
                            L10n.t(NodesActivity.this, "已请求切换到 ", "Switch requested: ") + n.name,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(NodesActivity.this, L10n.t(NodesActivity.this,
                            "已记住该节点，连接后生效", "Node saved. It will be used when connected."),
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
        testButton.setText(L10n.t(this, "测试中…", "Testing…"));
        testStatus = L10n.t(this, "正在进行代理 HTTP 健康检查（内核测试整组，显示当前筛选结果）",
                "Checking proxy HTTP health (the core tests the whole group; only filtered results are shown)");
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
                        testButton.setText(L10n.t(NodesActivity.this, "HTTP 测延迟", "HTTP latency"));
                        testStatus = result.isCurrent() ? batchStatus(result) : L10n.t(NodesActivity.this,
                                "配置已变更，测试结果已丢弃", "Configuration changed. Test results discarded.");
                        render();
                        Toast.makeText(NodesActivity.this, testStatus, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** Translate only the app-owned Latency result protocol, never node names or unknown errors. */
    private String batchStatus(Latency.BatchResult result) {
        String message = result.message == null ? "" : result.message;
        // Match the producer's exact summary; this raw text is not presented directly.
        String originalSummary = "HTTP：成功 " + result.succeeded + "，失败 " + result.failed
                + "，未取得结果 " + result.unavailable;
        String summary = String.format(Locale.US, L10n.t(this,
                "HTTP：成功 %1$d，失败 %2$d，未取得结果 %3$d",
                "HTTP: %1$d succeeded, %2$d failed, %3$d without results"),
                result.succeeded, result.failed, result.unavailable);
        if (message.equals(originalSummary)) return summary;
        String suffix = "\n" + originalSummary;
        if (message.endsWith(suffix)) {
            return batchReason(message.substring(0, message.length() - suffix.length())) + "\n" + summary;
        }
        return batchReason(message);
    }

    private String batchReason(String reason) {
        switch (reason) {
            case "此测试请求已使用，请重新测试":
                return L10n.t(this, "此测试请求已使用，请重新测试", "Test request already used. Run a new test.");
            case "HTTP 测试已取消":
                return L10n.t(this, "HTTP 测试已取消", "HTTP test cancelled");
            case "内核仍在处理上次 HTTP 测试，请稍后重试":
                return L10n.t(this, "内核仍在处理上次 HTTP 测试，请稍后重试",
                        "The core is still processing the previous HTTP test. Try again later.");
            case "没有可测试的节点":
                return L10n.t(this, "没有可测试的节点", "No nodes available to test");
            case "内核正在切换配置，HTTP 测试未开始":
                return L10n.t(this, "内核正在切换配置，HTTP 测试未开始",
                        "The core is switching configurations. The HTTP test did not start.");
            case "测试已取消或配置已变更":
                return L10n.t(this, "测试已取消或配置已变更", "Test cancelled or configuration changed");
            case "请先连接后测代理延迟（未启动 VPN，也未改用 TCP）":
                return L10n.t(this, "请先连接后测代理延迟（未启动 VPN，也未改用 TCP）",
                        "Connect before testing proxy latency (VPN was not started and TCP was not substituted)");
            case "当前配置的 HTTP 测试组不含所选节点":
                return L10n.t(this, "当前配置的 HTTP 测试组不含所选节点",
                        "The current HTTP test group does not include the selected nodes");
            case "内核 HTTP 测试尚未完成；未取得结果，不代表节点超时":
                return L10n.t(this, "内核 HTTP 测试尚未完成；未取得结果，不代表节点超时",
                        "The core HTTP test is not finished. No result does not mean the node timed out.");
            case "内核无法完成 HTTP 测试；未取得节点结果":
                return L10n.t(this, "内核无法完成 HTTP 测试；未取得节点结果",
                        "The core could not finish the HTTP test; no node results were obtained");
            case "内核返回的 HTTP 测试格式无法识别，未使用历史或 TCP 数据":
                return L10n.t(this, "内核返回的 HTTP 测试格式无法识别，未使用历史或 TCP 数据",
                        "Unrecognized HTTP test result format. Historical or TCP data was not used.");
            case "HTTP 测试不可用：内核或配置未就绪":
                return L10n.t(this, "HTTP 测试不可用：内核或配置未就绪",
                        "HTTP test unavailable: the core or configuration is not ready");
            case "HTTP 测试不可用：内核接口不兼容":
                return L10n.t(this, "HTTP 测试不可用：内核接口不兼容",
                        "HTTP test unavailable: incompatible core interface");
            case "测试已取消或配置已变更，结果已丢弃":
                return L10n.t(this, "测试已取消或配置已变更，结果已丢弃",
                        "Test cancelled or configuration changed; results discarded");
            case "部分节点无可确认结果（缺失、异常或未识别的状态）；不能视为成功":
                return L10n.t(this, "部分节点无可确认结果（缺失、异常或未识别的状态）；不能视为成功",
                        "Some nodes have no confirmed result (missing, invalid or unrecognized state); they cannot count as successful");
            default:
                return reason;
        }
    }
}
