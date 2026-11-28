package io.ghx.vpn;

import io.ghx.vpn.ui.BaseActivity;
import io.ghx.vpn.ui.L10n;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.ghx.vpn.core.Latency;
import io.ghx.vpn.core.Prefs;
import io.ghx.vpn.core.Session;
import io.ghx.vpn.core.SubscriptionManager;
import io.ghx.vpn.net.DnsCheck;
import io.ghx.vpn.net.IpInfo;
import io.ghx.vpn.net.SpeedTest;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.svc.TunnelService;
import io.ghx.vpn.ui.Format;
import io.ghx.vpn.ui.Ui;

/** 首页：连接开关 / 模式 / 当前节点 / 出口 IP / 测速。 */
public class MainActivity extends BaseActivity {

    private static final int REQ_VPN = 100;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private SubscriptionManager subs;

    private TextView statusView;
    private TextView statusSub;
    private TextView toggleBtn;
    private TextView nodeNameView;
    private TextView nodeInfoView;

    private TextView ipFlagView;
    private TextView ipAddrView;
    private TextView ipMetaView;
    private TextView dnsView;
    private TextView speedView;
    private io.ghx.vpn.ui.TrafficPanel trafficPanel;

    private LinearLayout modeRow;

    private boolean pendingStart;
    private boolean ipBusy;
    private boolean stopped = true;
    private SpeedTest.Cancellation speedCancellation;
    private static final long NETWORK_INTERVAL_MS = 10000L;
    private long networkEpoch;
    private String networkRoute;
    private boolean networkRefreshPending;
    private TextView networkUpdateView;
    private android.net.ConnectivityManager connectivity;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable networkRefresh = new Runnable() {
        @Override public void run() { if (!stopped) detectNetwork(); }
    };
    private final Runnable networkChanged = new Runnable() {
        @Override public void run() { if (!stopped) checkNetworkRoute(); }
    };

    @Override protected void onStop() {
        stopped = true;
        if (trafficPanel != null) trafficPanel.pause();
        ++networkEpoch;
        networkRefreshPending = false;
        ui.removeCallbacks(networkRefresh);
        ui.removeCallbacks(networkChanged);
        ui.removeCallbacks(tick);
        unregisterNetworkCallback();
        if (speedCancellation != null) speedCancellation.cancel();
        super.onStop();
    }

    /** onResume 可能被多次调用：先注销旧的，避免回调重复注册后泄漏。 */
    private void unregisterNetworkCallback() {
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        }
        networkCallback = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        subs = new SubscriptionManager(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        setContentView(build());

        io.ghx.vpn.core.FileImport.runOnce(this);
        // VPN 只能由明确的连接操作启动，不接受外部调试标记自动连接。
        new io.ghx.vpn.core.Prefs(this).migrateModeOnce();
        refresh();
        io.ghx.vpn.core.Diag.dump(this, "start");
        io.ghx.vpn.core.AutoUpdate.runIfDue(this, new io.ghx.vpn.core.AutoUpdate.Callback() {
            @Override
            public void onDone(int updated, int failed) {
                if (updated > 0) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshNode();
                        }
                    });
                }
            }
        });
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAppearanceRecreating()) return;
        io.ghx.vpn.core.AppUpdates.autoCheck(this);
        stopped = false;
        if (trafficPanel != null) trafficPanel.resume();
        refresh();
        connectivity = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        unregisterNetworkCallback();
        networkCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network network) { ui.post(networkChanged); }
            @Override public void onLost(android.net.Network network) { ui.post(networkChanged); }
            @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities c) {
                ui.post(networkChanged);
            }
            @Override public void onLinkPropertiesChanged(android.net.Network n, android.net.LinkProperties p) {
                ui.post(networkChanged);
            }
        };
        try { if (connectivity != null) connectivity.registerDefaultNetworkCallback(networkCallback); }
        catch (RuntimeException ignored) { networkCallback = null; }
        networkRoute = null;
        checkNetworkRoute();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onDestroy() {
        if (speedCancellation != null) speedCancellation.cancel();
        ui.removeCallbacks(tick);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            refreshStatus();
            checkNetworkRoute();
            ui.postDelayed(this, 1000);
        }
    };

    // ---------------- 界面 ----------------

    private View build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);

        LinearLayout root = Ui.column(this);
        int p = Ui.dp(this, 18);
        root.setPadding(p, Ui.dp(this, 26), p, Ui.dp(this, 30));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶栏
        LinearLayout head = Ui.row(this);
        TextView title = Ui.text(this, "轻连", 23f, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(-0.02f);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView subsBtn = headerAction(L10n.t(this, "订阅", "Subscriptions"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SubsActivity.class));
            }
        });
        head.addView(subsBtn);
        TextView setBtn = headerAction(L10n.t(this, "设置", "Settings"), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        head.addView(setBtn);
        root.addView(head);

        // 主状态卡（渐变压顶）
        LinearLayout hero = Ui.column(this);
        hero.setBackground(Ui.gradient(this, Ui.CARD_2, Ui.CARD, 20f));
        int hp = Ui.dp(this, 18);
        hero.setPadding(hp, hp, hp, hp);

        statusView = Ui.text(this, L10n.t(this, "未连接", "Disconnected"), 20f, Ui.TEXT);
        statusView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hero.addView(statusView);

        statusSub = Ui.text(this, L10n.t(this, "点下方按钮开始使用", "Tap the button below to get started"), 12f, Ui.TEXT_MID);
        statusSub.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 16));
        hero.addView(statusSub);

        toggleBtn = Ui.text(this, L10n.t(this, "连接", "Connect"), 15f, Ui.ON_ACCENT);
        toggleBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toggleBtn.setGravity(Gravity.CENTER);
        toggleBtn.setBackground(Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_2, 13f));
        toggleBtn.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        hero.addView(toggleBtn);
        root.addView(hero);
        Ui.margin(this, hero, 0, 16, 0, 12);

        // 主页主入口：管理订阅
        LinearLayout addSub = Ui.row(this);
        addSub.setGravity(Gravity.CENTER_VERTICAL);
        addSub.setMinimumHeight(Ui.dp(this, 76));
        addSub.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        addSub.setBackground(Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_2, 16f));
        android.widget.ImageView addIcon = new android.widget.ImageView(this);
        addIcon.setImageResource(R.drawable.ic_add_subscription);
        addIcon.setColorFilter(Ui.ON_ACCENT);
        addSub.addView(addIcon, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        LinearLayout addLabels = Ui.column(this);
        addLabels.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView addTitle = Ui.text(this, L10n.t(this, "管理订阅", "Manage subscriptions"), 18f, Ui.ON_ACCENT);
        addTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        addLabels.addView(addTitle);
        addLabels.addView(Ui.text(this, L10n.t(this, "添加、更新与切换订阅", "Add, update and switch subscriptions"), 12f, Ui.ON_ACCENT));
        addSub.addView(addLabels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addSub.addView(Ui.text(this, "›", 26f, Ui.ON_ACCENT));
        addSub.setContentDescription(L10n.t(this, "管理订阅，添加、更新与切换订阅",
                "Manage subscriptions: add, update and switch subscriptions"));
        addSub.setFocusable(true);
        addSub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SubsActivity.class));
            }
        });
        root.addView(addSub);
        Ui.margin(this, addSub, 0, 0, 0, 12);

        trafficPanel = new io.ghx.vpn.ui.TrafficPanel(this);
        root.addView(trafficPanel);
        Ui.margin(this, trafficPanel, 0, 0, 0, 12);

        // 模式
        LinearLayout modeCard = Ui.card(this);
        modeCard.addView(Ui.text(this, L10n.t(this, "代理模式", "Proxy mode"), 11f, Ui.TEXT_DIM));
        modeRow = Ui.row(this);
        modeRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        modeCard.addView(modeRow);
        root.addView(modeCard);
        Ui.margin(this, modeCard, 0, 0, 0, 12);

        // 节点
        LinearLayout nodeCard = Ui.card(this);
        nodeCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NodesActivity.class));
            }
        });
        LinearLayout nTop = Ui.row(this);
        nTop.addView(Ui.text(this, L10n.t(this, "当前节点", "Current node"), 11f, Ui.TEXT_DIM),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nTop.addView(Ui.text(this, L10n.t(this, "选择 ›", "Select ›"), 11f, Ui.ACCENT_TEXT));
        nodeCard.addView(nTop);

        nodeNameView = Ui.text(this, "-", 15f, Ui.TEXT);
        nodeNameView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nodeNameView.setPadding(0, Ui.dp(this, 8), 0, 0);
        nodeCard.addView(nodeNameView);

        nodeInfoView = Ui.text(this, "", 11f, Ui.TEXT_DIM);
        nodeInfoView.setPadding(0, Ui.dp(this, 4), 0, 0);
        nodeCard.addView(nodeInfoView);
        root.addView(nodeCard);
        Ui.margin(this, nodeCard, 0, 0, 0, 12);

        // 网络检测（出口 IP / DNS）
        LinearLayout netCard = Ui.card(this);
        LinearLayout netHead = Ui.row(this);
        netHead.addView(Ui.text(this, L10n.t(this, "网络检测", "Network check"), 11f, Ui.TEXT_DIM),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refreshIp = Ui.text(this, L10n.t(this, "刷新", "Refresh"), 11f, Ui.ACCENT_TEXT);
        refreshIp.setPadding(Ui.dp(this, 8), 0, 0, 0);
        refreshIp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                detectNetwork();
            }
        });
        netHead.addView(refreshIp);
        netCard.addView(netHead);

        LinearLayout ipRow = Ui.row(this);
        ipRow.setPadding(0, Ui.dp(this, 12), 0, 0);

        ipFlagView = Ui.text(this, "", 26f, Ui.TEXT);
        ipFlagView.setGravity(Gravity.CENTER);
        ipFlagView.setMinWidth(Ui.dp(this, 40));
        ipRow.addView(ipFlagView);

        LinearLayout ipTexts = Ui.column(this);
        ipTexts.setPadding(Ui.dp(this, 10), 0, 0, 0);
        ipAddrView = Ui.text(this, L10n.t(this, "检测中…", "Checking…"), 15f, Ui.TEXT);
        ipAddrView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ipTexts.addView(ipAddrView);
        ipMetaView = Ui.text(this, "", 11f, Ui.TEXT_DIM);
        ipMetaView.setPadding(0, Ui.dp(this, 4), 0, 0);
        ipTexts.addView(ipMetaView);
        ipRow.addView(ipTexts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        netCard.addView(ipRow);

        netCard.addView(Ui.divider(this));

        dnsView = Ui.text(this, L10n.t(this, "DNS 解析 · 未检测", "DNS resolution · Not checked"), 12f, Ui.TEXT_MID);
        netCard.addView(dnsView);
        networkUpdateView = Ui.text(this, L10n.t(this, "自动更新 · 每10秒", "Auto-refresh · Every 10 seconds"), 10f, Ui.TEXT_DIM);
        networkUpdateView.setPadding(0, Ui.dp(this, 8), 0, 0);
        netCard.addView(networkUpdateView);
        TextView netScope = Ui.text(this, L10n.t(this, "仅检测本机/代理出口，不代表其他应用的 VPN 通路已验证",
                "Checks only this device or proxy egress; it does not verify other apps' VPN routes"), 10f, Ui.TEXT_DIM);
        netScope.setPadding(0, Ui.dp(this, 4), 0, 0);
        netCard.addView(netScope);

        root.addView(netCard);
        Ui.margin(this, netCard, 0, 0, 0, 12);

        // 测速
        LinearLayout speedCard = Ui.card(this);
        LinearLayout spHead = Ui.row(this);
        spHead.addView(Ui.text(this, L10n.t(this, "速度测试", "Speed test"), 11f, Ui.TEXT_DIM),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView runSpeed = Ui.text(this, L10n.t(this, "开始", "Start"), 11f, Ui.ACCENT_TEXT);
        runSpeed.setPadding(Ui.dp(this, 8), 0, 0, 0);
        runSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSpeedTest();
            }
        });
        spHead.addView(runSpeed);
        speedCard.addView(spHead);

        speedView = Ui.text(this, L10n.t(this, "点「开始」完整测速（约 15 秒）", "Tap Start for a full speed test (about 15 seconds)"), 12f, Ui.TEXT_MID);
        speedView.setLineSpacing(Ui.dp(this, 3), 1f);
        speedView.setPadding(0, Ui.dp(this, 10), 0, 0);
        speedCard.addView(speedView);
        root.addView(speedCard);

        return sv;
    }

    private TextView headerAction(String text, View.OnClickListener l) {
        TextView t = Ui.text(this, text, 13f, Ui.TEXT_MID);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        t.setOnClickListener(l);
        return t;
    }

    // ---------------- 刷新 ----------------

    private void refresh() {
        refreshMode();
        refreshNode();
        refreshStatus();
    }

    private void refreshMode() {
        modeRow.removeAllViews();
        String cur = prefs.mode();
        final String[][] modes = {{"direct", L10n.t(this, "直连", "Direct")},
                {"rule", L10n.t(this, "规则", "Rule")}, {"global", L10n.t(this, "全局", "Global")}};
        for (int i = 0; i < modes.length; i++) {
            final String key = modes[i][0];
            boolean on = key.equals(cur);
            TextView chip = Ui.chip(this, modes[i][1], on);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (key.equals(prefs.mode())) return;
                    prefs.mode(key);
                    refreshMode();
                    reloadIfRunning();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            modeRow.addView(chip, lp);
        }
    }

    private void refreshNode() {
        String sel = Session.selected();
        List<Node> list = Session.nodes();
        if (list.isEmpty()) {
            try {
                list = subs.collectNodes();
            } catch (RuntimeException error) {
                nodeNameView.setText(L10n.t(this, "配置读取失败", "Could not read configuration"));
                nodeInfoView.setText(L10n.error(this, error));
                return;
            }
        }
        if (list.isEmpty()) {
            nodeNameView.setText(L10n.t(this, "未配置节点", "No nodes configured"));
            nodeNameView.setTextColor(Ui.TEXT_DIM);
            nodeInfoView.setText(L10n.t(this, "去「订阅」添加，或放到 import.txt 导入",
                    "Add a subscription, or import it via import.txt"));
            return;
        }
        if (sel == null) sel = list.get(0).name;
        Node n = find(list, sel);
        // The generated selector is UI-owned; real subscription node names stay unchanged.
        nodeNameView.setText(n == null && Session.autoGroup().equals(sel)
                ? L10n.t(this, "自动选择", "Automatic selection") : sel);
        nodeNameView.setTextColor(Ui.TEXT);
        String info = (n == null ? "" : n.type.toUpperCase() + " · " + n.server + ":" + n.port);
        Integer ms = Latency.get(sel);
        if (ms != null) info = info + " · " + Format.latency(this, ms);
        nodeInfoView.setText(info);
    }

    private static Node find(List<Node> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) return list.get(i);
        }
        return null;
    }

    private void refreshStatus() {
        boolean running = TunnelService.isRunning();
        if (TunnelService.isStarting()) {
            statusView.setText(L10n.t(this, "正在连接…", "Connecting…"));
            statusSub.setText(L10n.t(this, "可点取消停止连接", "Tap Cancel to stop connecting"));
            toggleBtn.setText(L10n.t(this, "取消", "Cancel"));
            return;
        }
        if (running) {
            statusView.setText(L10n.t(this, "已连接", "Connected"));
            statusView.setTextColor(Ui.OK);
            statusSub.setText(L10n.t(this, "VPN 接口已建立 · ", "VPN interface established · ") + modeLabel(prefs.mode()));
            toggleBtn.setText(L10n.t(this, "断开", "Disconnect"));
            toggleBtn.setBackground(Ui.stroke(this, Ui.CARD_2, Ui.LINE, 13f, 1f));
            toggleBtn.setTextColor(Ui.TEXT);
        } else {
            String err = TunnelService.lastError();
            if (err != null) {
                statusView.setText(L10n.t(this, "连接失败", "Connection failed"));
                statusView.setTextColor(Ui.ERR);
                statusSub.setText(L10n.message(this, err));
            } else {
                statusView.setText(L10n.t(this, "未连接", "Disconnected"));
                statusView.setTextColor(Ui.TEXT);
                statusSub.setText(L10n.t(this, "点下方按钮开始使用", "Tap the button below to get started"));
            }
            toggleBtn.setText(L10n.t(this, "连接", "Connect"));
            toggleBtn.setBackground(Ui.gradient(this, Ui.ACCENT, Ui.ACCENT_2, 13f));
            toggleBtn.setTextColor(Ui.ON_ACCENT);
        }
    }

    private String modeLabel(String m) {
        if ("rule".equals(m)) return L10n.t(this, "规则", "Rule");
        if ("global".equals(m)) return L10n.t(this, "全局", "Global");
        return L10n.t(this, "直连", "Direct");
    }

    // ---------------- IP / DNS ----------------

    /** Route identity also catches app proxy changes that Android's default-network callback misses. */
    private String currentNetworkRoute() {
        String system = "unknown";
        try {
            android.net.Network n = connectivity == null ? null : connectivity.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : connectivity.getNetworkCapabilities(n);
            android.net.LinkProperties p = n == null ? null : connectivity.getLinkProperties(n);
            system = n == null ? "offline" : n.toString() + ":"
                    + (c == null ? "" : c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    + ":" + c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    + ":" + c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    + ":" + (p == null ? "" : p.getLinkAddresses().toString() + p.getDnsServers().toString());
        } catch (RuntimeException ignored) {}
        return system + ":" + TunnelService.isRunning() + ":" + TunnelService.isStarting()
                + ":" + prefs.mixedPort() + ":" + prefs.mode() + ":" + Session.selected();
    }

    private void checkNetworkRoute() {
        String route = currentNetworkRoute();
        if (route.equals(networkRoute)) return;
        networkRoute = route;
        ++networkEpoch;
        ipAddrView.setText(L10n.t(this, "检测中…", "Checking…"));
        ipAddrView.setTextColor(Ui.TEXT_DIM);
        ipFlagView.setText("");
        ipMetaView.setText(L10n.t(this, "网络或代理状态已变化", "Network or proxy status changed"));
        dnsView.setText(L10n.t(this, "DNS 解析 · 等待更新", "DNS resolution · Waiting for update"));
        networkUpdateView.setText(L10n.t(this, "网络变化 · 正在更新", "Network changed · Updating"));
        ui.removeCallbacks(networkRefresh);
        ui.postDelayed(networkRefresh, 300); // merge callback bursts
    }

    private void detectNetwork() {
        if (stopped || isDestroyed()) return;
        ui.removeCallbacks(networkRefresh);
        if (ipBusy) { networkRefreshPending = true; return; }
        ipBusy = true;
        networkRefreshPending = false;
        final long epoch = networkEpoch;
        final String route = currentNetworkRoute();
        final int port = TunnelService.isRunning() ? prefs.mixedPort() : 0;
        final long began = android.os.SystemClock.elapsedRealtime();
        networkUpdateView.setText(L10n.t(this, "自动更新 · 检测中…", "Auto-refresh · Checking…"));
        new Thread(new Runnable() {
            @Override public void run() {
                IpInfo foundIp = null;
                DnsCheck foundDns = null;
                try {
                    foundIp = IpInfo.detect(port, 2500);
                    foundDns = DnsCheck.run(port, 2500);
                } catch (RuntimeException ignored) {}
                final IpInfo info = foundIp;
                final DnsCheck dns = foundDns;
                ui.post(new Runnable() {
                    @Override public void run() {
                        ipBusy = false;
                        if (stopped || isDestroyed()) return;
                        if (epoch != networkEpoch || !route.equals(currentNetworkRoute())) {
                            checkNetworkRoute();
                            ui.removeCallbacks(networkRefresh);
                            ui.post(networkRefresh);
                            return;
                        }
                        showIp(info);
                        showDns(dns, port > 0);
                        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(new java.util.Date());
                        networkUpdateView.setText(String.format(java.util.Locale.US,
                                L10n.t(MainActivity.this, "自动更新 · %1$s · %2$s · 每10秒",
                                        "Auto-refresh · %1$s · %2$s · Every 10 seconds"),
                                port > 0 ? L10n.t(MainActivity.this, "代理出口", "Proxy egress")
                                        : L10n.t(MainActivity.this, "本机网络", "Device network"), time));
                        long delay = networkRefreshPending ? 300 : Math.max(1000,
                                NETWORK_INTERVAL_MS - (android.os.SystemClock.elapsedRealtime() - began));
                        ui.postDelayed(networkRefresh, delay);
                    }
                });
            }
        }, "detect").start();
    }

    private void showIp(IpInfo info) {
        if (info == null || info.ip == null || info.ip.length() == 0) {
            ipFlagView.setText("🌐");
            ipAddrView.setText(L10n.t(this, "检测失败", "Check failed"));
            ipAddrView.setTextColor(Ui.ERR);
            ipMetaView.setText(L10n.t(this, "网络不可用或接口被拦截", "Network unavailable or endpoint blocked"));
            return;
        }
        String flag = info.flag();
        ipFlagView.setText(flag.length() > 0 ? flag : "🌐");
        ipAddrView.setText(info.ip);
        ipAddrView.setTextColor(Ui.TEXT);

        StringBuilder meta = new StringBuilder();
        if (info.countryCode != null && info.countryCode.length() == 2) {
            meta.append(info.countryCode.toUpperCase(java.util.Locale.ROOT));
        }
        if (info.country != null && info.country.length() > 0
                && !info.country.equals(info.countryCode)) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(info.country);
        }
        if (info.city != null && info.city.length() > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(info.city);
        }
        if (info.isp != null && info.isp.length() > 0) {
            if (meta.length() > 0) meta.append("\n");
            meta.append(info.isp);
        }
        ipMetaView.setText(meta.toString());
        ipMetaView.setTextColor(Ui.TEXT_DIM);
    }

    private void showDns(DnsCheck dns, boolean viaProxy) {
        if (dns == null || !dns.ok()) {
            dnsView.setText(L10n.t(this, "DNS 解析 · 失败", "DNS resolution · Failed"));
            dnsView.setTextColor(Ui.ERR);
            return;
        }
        if (viaProxy && dns.proxyMs < 0) {
            // 开了代理却只测到本机解析：不能标成绿色成功。
            dnsView.setText(String.format(java.util.Locale.US,
                    L10n.t(this, "DNS 解析 · 代理未通（本机 %s）", "DNS resolution · Proxy unreachable (device: %s)"),
                    dns.systemMs >= 0 ? dns.systemMs + " ms" : L10n.t(this, "失败", "Failed")));
            dnsView.setTextColor(Ui.WARN);
            return;
        }
        StringBuilder sb = new StringBuilder(L10n.t(this, "DNS 解析 ", "DNS resolution "));
        if (viaProxy && dns.proxyMs >= 0) {
            sb.append(L10n.t(this, "域名代理连接 ", "Proxy connection by domain "))
                    .append(dns.proxyMs).append(L10n.t(this, " ms（含握手）", " ms (including handshake)"));
        } else if (dns.systemMs >= 0) {
            sb.append(L10n.t(this, "本机 ", "Device ")).append(dns.systemMs).append(" ms");
        }
        if (dns.resolved != null) sb.append("  ·  ").append(dns.resolved);
        dnsView.setText(sb.toString());
        long ms = viaProxy && dns.proxyMs >= 0 ? dns.proxyMs : dns.systemMs;
        dnsView.setTextColor(ms < 200 ? Ui.OK : ms < 600 ? Ui.WARN : Ui.ERR);
    }

    // ---------------- 测速 ----------------

    /** 完整测速：跑满 15 秒，报告平均与峰值。 */
    private void runSpeedTest() {
        if (speedCancellation != null) {
            Toast.makeText(this, L10n.t(this, "已有测速正在进行", "A speed test is already running"), Toast.LENGTH_SHORT).show();
            return;
        }
        final SpeedTest.Cancellation cancellation = new SpeedTest.Cancellation();
        speedCancellation = cancellation;
        speedView.setText(L10n.t(this, "测速中… 最长15秒 / 下载体上限300MiB",
                "Testing… Up to 15 seconds / 300 MiB download-body limit"));
        speedView.setTextColor(Ui.TEXT_MID);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int port = TunnelService.isRunning() ? prefs.mixedPort() : 0;
                final SpeedTest r = SpeedTest.run(port, 15000, 300L * 1024 * 1024, new SpeedTest.Progress() {
                    @Override
                    public void onProgress(final double now, final double avg,
                                           final long bytes, final long costMs) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (stopped || isDestroyed() || cancellation.isCancelled()) return;
                                speedView.setText(String.format(java.util.Locale.US,
                                        L10n.t(MainActivity.this, "%.1f Mbps  ·  平均 %.1f  ·  %s / %.0fs",
                                                "%.1f Mbps  ·  Average %.1f  ·  %s / %.0fs"),
                                        now, avg, Format.bytes(bytes), costMs / 1000.0));
                                speedView.setTextColor(Ui.TEXT_MID);
                            }
                        });
                    }
                }, cancellation);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (speedCancellation == cancellation) speedCancellation = null;
                        if (isDestroyed()) return;
                        if (r.cancelled || cancellation.isCancelled()) {
                            speedView.setText(L10n.t(MainActivity.this, "测速已取消", "Speed test cancelled"));
                            return;
                        }
                        if (stopped) return;
                        if (r.error != null) {
                            speedView.setText(L10n.t(MainActivity.this, "测速失败：", "Speed test failed: ")
                                    + L10n.message(MainActivity.this, r.error));
                            speedView.setTextColor(Ui.ERR);
                            return;
                        }
                        String head = String.format(java.util.Locale.US, "%.2f Mbps", r.mbpsAvg);
                        String tail = String.format(java.util.Locale.US,
                                L10n.t(MainActivity.this, "峰值 %.2f  ·  %s / %.1fs  ·  %d 线程",
                                        "Peak %.2f  ·  %s / %.1fs  ·  %d threads"),
                                r.mbpsPeak, Format.bytes(r.bytes), r.costMs / 1000.0, r.streams);
                        if (r.source != null && r.source.length() > 0) {
                            tail = tail + "\n" + r.source;
                        }
                        if (r.warning != null && r.warning.length() > 0) {
                            tail += "\n" + L10n.t(MainActivity.this, "部分测速源失败，结果仅供参考",
                                    "Some test sources failed; results are for reference only");
                        }
                        speedView.setText(head + "\n" + tail);
                        speedView.setTextColor(r.mbpsAvg > 20 ? Ui.OK
                                : r.mbpsAvg > 5 ? Ui.WARN : Ui.ERR);
                    }
                });
            }
        }, "speedtest").start();
    }
    // ---------------- 操作 ----------------

    private void reloadIfRunning() {
        if (!io.ghx.vpn.core.SessionTasks.admitReload(TunnelService.isRunning(), TunnelService.isStarting())) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    io.ghx.vpn.core.SessionTasks.runWhenRunning(Session.CORE_LOCK,
                            new io.ghx.vpn.core.SessionTasks.State() {
                                public boolean running() { return TunnelService.isRunning() && Session.isKernelRunning(); }
                            }, new io.ghx.vpn.core.SessionTasks.Task() {
                                public void run() throws Exception {
                                    Session.reload(MainActivity.this, new SubscriptionManager(MainActivity.this));
                                }
                            });
                } catch (final Exception error) {
                    ui.post(new Runnable() { public void run() {
                        if (!isDestroyed()) Toast.makeText(MainActivity.this,
                                L10n.t(MainActivity.this, "配置未生效：", "Configuration not applied: ")
                                        + L10n.error(MainActivity.this, error), Toast.LENGTH_LONG).show();
                    }});
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                });
            }
        }, "reload").start();
    }

    private void toggle() {
        if (TunnelService.isRunning() || TunnelService.isStarting()) {
            if (speedCancellation != null) speedCancellation.cancel();
            TunnelService.stop(this);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshStatus();
                }
            }, 400);
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            pendingStart = true;
            startActivityForResult(prep, REQ_VPN);
            return;
        }
        doStart();
    }

    private void doStart() {
        if (TunnelService.isStarting()) return;
        if (subs.list().isEmpty()) {
            Toast.makeText(this, L10n.t(this, "请先添加订阅", "Add a subscription first"), Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SubsActivity.class));
            return;
        }
        TunnelService.start(this);
        statusView.setText(L10n.t(this, "正在连接…", "Connecting…"));
        statusView.setTextColor(Ui.WARN);
        statusSub.setText(L10n.t(this, "正在建立隧道", "Establishing tunnel"));
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                if (TunnelService.isRunning()) detectNetwork();
            }
        }, 2200);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            boolean ok = resultCode == RESULT_OK && pendingStart;
            pendingStart = false;
            if (ok) doStart();
            else Toast.makeText(this, L10n.t(this, "未授权 VPN，无法连接",
                    "VPN permission not granted. Cannot connect."), Toast.LENGTH_SHORT).show();
        }
    }
}
