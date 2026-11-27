package io.ghx.vpn.core;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.ghx.vpn.net.Downloader;
import io.ghx.vpn.sub.ClashYaml;
import io.ghx.vpn.sub.Node;
import io.ghx.vpn.sub.SubParser;

/** 订阅的拉取、格式识别、缓存与自动更新判定。 */
public final class SubscriptionManager {

    private final Context ctx;
    private final ProfileStore store;

    public SubscriptionManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.store = new ProfileStore(this.ctx);
    }

    public ProfileStore store() {
        return store;
    }

    public List<Profile> list() {
        return store.load();
    }

    /** 拉取并验证后更新 p；调用 update(p) 才持久化。Clash 返回 -1（内核解析）。 */
    public int refresh(Profile p) throws IOException {
        if (!p.hasUrl()) throw new IOException("没有订阅地址");
        Downloader.Result r = Downloader.fetch(p.url);
        String raw = r.body == null ? "" : r.body;
        if (raw.trim().length() == 0) throw new IOException("订阅内容为空");

        String text = SubParser.unwrapBase64(raw);
        int fmt = SubParser.detect(text);
        final int count;
        try {
            count = validatedNodeCount(text, fmt);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        // Validate first: unsupported/malformed URI feeds must not replace the last good cache.
        p.content = text;
        p.type = profileType(fmt);
        p.updatedAt = System.currentTimeMillis();
        p.lastError = null;
        Log.i("订阅已更新 " + p.name + " 格式=" + fmt + " 长度=" + text.length());
        return count;
    }

    private static int profileType(int fmt) {
        return fmt == SubParser.FMT_CLASH ? Profile.TYPE_CLASH
                : fmt == SubParser.FMT_SURFBOARD ? Profile.TYPE_SURFBOARD
                : fmt == SubParser.FMT_QL ? Profile.TYPE_QL : Profile.TYPE_URI;
    }

    private static int validatedNodeCount(String text, int fmt) {
        if (fmt == SubParser.FMT_UNKNOWN) {
            String low = text.trim().toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("<html") || low.startsWith("<!doctype")) {
                throw new IllegalArgumentException("返回的是网页，可能订阅地址失效或需要 UA");
            }
            throw new IllegalArgumentException("无法识别的订阅格式");
        }
        // The lightweight Clash reader cannot validate all YAML/provider configurations.
        if (fmt == SubParser.FMT_CLASH) return -1;
        int count = SubParser.parseNodes(text, fmt).size();
        if (count == 0) throw new IllegalArgumentException("订阅没有可解析的节点");
        return count;
    }

    /** 收藏/新增订阅。 */
    public Profile add(String name, String url, int autoUpdateMinutes) {
        synchronized (ProfileStore.LOCK) {
            List<Profile> all = store.load();
            // 只有地址时格式未知，等第一次下载成功再定型（之前一律写成 v2ray，界面上会误报）。
            Profile p = new Profile("p" + UUID.randomUUID().toString(), name, url, Profile.TYPE_UNKNOWN);
            p.autoUpdateMinutes = Interval.normalize(autoUpdateMinutes);
            all.add(p);
            store.save(all);
            return p;
        }
    }

    /** 手动粘贴内容导入；拒绝无法识别或没有可用节点的内容。 */
    public Profile addRaw(String name, String content, int autoUpdateMinutes) {
        String text = SubParser.unwrapBase64(content);
        int fmt = SubParser.detect(text);
        validatedNodeCount(text, fmt);
        synchronized (ProfileStore.LOCK) {
            List<Profile> all = store.load();
            Profile p = new Profile("p" + UUID.randomUUID().toString(), name, "", profileType(fmt));
            p.content = text;
            p.autoUpdateMinutes = Interval.normalize(autoUpdateMinutes);
            p.updatedAt = System.currentTimeMillis();
            all.add(p);
            store.save(all);
            Log.i("订阅已添加 " + p.name + " 格式=" + fmt + " 长度=" + text.length());
            return p;
        }
    }

    /**
     * 从 URL 导入：保存订阅地址和已校验过的内容，保存后即参与自动更新。
     * 内容来自用户已预览的那次下载，因此不重复请求；updatedAt 由这次下载时间决定。
     */
    public Profile addRemote(String name, String url, String content, int autoUpdateMinutes) {
        if (url == null || url.trim().length() == 0) throw new IllegalArgumentException("缺少订阅地址");
        String text = SubParser.unwrapBase64(content);
        int fmt = SubParser.detect(text);
        validatedNodeCount(text, fmt);
        synchronized (ProfileStore.LOCK) {
            List<Profile> all = store.load();
            Profile p = new Profile("p" + UUID.randomUUID().toString(), name, url, profileType(fmt));
            p.content = text;
            p.autoUpdateMinutes = Interval.normalize(autoUpdateMinutes);
            p.updatedAt = System.currentTimeMillis();
            p.lastError = null;
            all.add(p);
            store.save(all);
            Log.i("订阅已从链接导入 " + p.name + " 格式=" + fmt + " 自动=" + p.autoUpdateMinutes + "m");
            return p;
        }
    }

    /** Merge only refresh output; never overwrite settings from a stale worker/UI snapshot. */
    public void update(Profile p) {
        synchronized (ProfileStore.LOCK) {
            List<Profile> all = store.load();
            Profile current = store.byId(all, p.id);
            if (current == null) return; // Deleted while downloading: do not recreate it.
            if (!sameUrl(current.url, p.url) || p.updatedAt < current.updatedAt) return;
            if (p.lastError == null) {
                current.content = p.content;
                current.type = p.type;
                current.updatedAt = p.updatedAt;
            }
            current.lastError = p.lastError;
            store.save(all);
        }
    }

    private static boolean sameUrl(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Update only this setting, preserving a concurrent refresh's cached content. */
    public void setAutoUpdateMinutes(String id, int minutes) {
        synchronized (ProfileStore.LOCK) {
            List<Profile> all = store.load();
            Profile current = store.byId(all, id);
            if (current == null) return;
            current.autoUpdateMinutes = Interval.normalize(minutes);
            store.save(all);
        }
    }

    public void remove(String id) {
        synchronized (ProfileStore.LOCK) {
            List<Profile> all = store.load();
            for (int i = all.size() - 1; i >= 0; i--) {
                if (all.get(i).id.equals(id)) all.remove(i);
            }
            store.save(all);
        }
    }

    /** 需要自动更新的订阅。 */
    public List<Profile> dueForUpdate() {
        List<Profile> out = new ArrayList<Profile>();
        if (new Prefs(ctx).autoUpdateMinutes() <= 0) return out;
        List<Profile> all = store.load();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.size(); i++) {
            Profile p = all.get(i);
            if (!p.enabled || !p.hasUrl() || Interval.periodMs(p.autoUpdateMinutes) == 0) continue;
            if (!Interval.due(p.updatedAt, p.autoUpdateMinutes, now)) continue;
            out.add(p);
        }
        return out;
    }

    // ---------------- 配置选用 ----------------

    /** 当前选用的配置；未指定时取第一个启用的。 */
    public Profile active() {
        List<Profile> all = store.load();
        String id = new Prefs(ctx).activeProfile();
        if (id != null && id.length() > 0) {
            for (int i = 0; i < all.size(); i++) {
                if (id.equals(all.get(i).id)) return all.get(i);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).enabled) return all.get(i);
        }
        return null;
    }

    /** 选用某个配置。 */
    public void select(String id) {
        new Prefs(ctx).activeProfile(id);
        Session.invalidate();
    }

    /** 供界面用：某个配置里的节点（Clash 原文也会被解析出来）。 */
    public List<Node> nodesOf(Profile p) {
        if (p == null || p.content == null || p.content.length() == 0) {
            return new ArrayList<Node>();
        }
        String text = p.decodedContent();
        int fmt = SubParser.detect(text);
        if (fmt == SubParser.FMT_CLASH) return ClashYaml.proxies(text);
        return SubParser.parseNodes(text, fmt);
    }

    /** 供界面用：某个配置里的分组（仅 Clash 原文有）。 */
    public List<ClashYaml.Group> groupsOf(Profile p) {
        if (p == null || p.type != Profile.TYPE_CLASH || p.content == null) {
            return new ArrayList<ClashYaml.Group>();
        }
        return ClashYaml.groups(p.decodedContent());
    }

    /** 当前选用配置的节点列表。 */
    public List<Node> collectNodes() {
        return nodesOf(active());
    }

    /** 当前选用配置若是 Clash 原文，返回它（保留其分组与规则）。 */
    public String clashBase() {
        // 调试用：外部 override.yaml 优先，便于不改包直接调配置
        try {
            java.io.File ov = new java.io.File(ctx.getExternalFilesDir(null), "override.yaml");
            if (ov.exists()) {
                String s = ProfileStore.readFile(ov);
                if (s != null && s.trim().length() > 0) return s;
            }
        } catch (Throwable ignored) {
        }
        Profile p = active();
        if (p != null && p.type == Profile.TYPE_CLASH && p.content != null
                && p.content.length() > 0) {
            return p.decodedContent();
        }
        return null;
    }

    /** 生成并写入当前生效的配置文件，返回文件路径。 */
    public File writeRuntimeConfig() {
        Prefs prefs = new Prefs(ctx);
        List<Node> nodes = collectNodes();
        String base = clashBase();

        ConfigBuilder b = new ConfigBuilder()
                .mode(prefs.mode())
                .mixedPort(prefs.mixedPort())
                .allowLan(prefs.allowLan())
                .tun(prefs.tun());
        if (base != null) b.base(base);
        else b.nodes(nodes);

        File f = new File(ctx.getFilesDir(), "runtime.yaml");
        try {
            ProfileStore.writeFile(f, b.build());
        } catch (IOException e) {
            throw new IllegalStateException("写入运行配置失败", e);
        }
        return f;
    }
}
