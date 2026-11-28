package io.ghx.vpn.core;

/** 一份节点配置：可来自订阅 URL，也可以是手动粘贴。 */
public final class Profile {

    /** 还没有下载成功：只有地址、没有内容，格式未知。 */
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_CLASH = 1;
    public static final int TYPE_URI = 2;
    public static final int TYPE_SURFBOARD = 3;
    /** 仅带 ql:// 或 ql: 显式包装的内容；普通 URI 列表始终是 TYPE_URI。 */
    public static final int TYPE_QL = 4;

    public String id;
    public String name;
    public String url;          // 订阅地址，手动导入时为空
    public String content;      // 配置原文
    public int type;
    public long updatedAt;      // 上次成功更新时间
    public int autoUpdateMinutes; // 0 = 不自动更新，最小 15 分钟
    public boolean enabled = true;
    public String lastError;
    /** In-process refresh ticket only; never persisted as subscription data. */
    transient long refreshTicket;

    public Profile() {
    }

    public Profile(String id, String name, String url, int type) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.type = type;
    }

    public boolean hasUrl() {
        return url != null && url.length() > 0;
    }

    public boolean isClash() {
        return type == TYPE_CLASH;
    }

    /** Resolve cached metadata from content, without rewriting content or any user setting. */
    public void reconcileFormat() {
        type = io.ghx.vpn.sub.SubParser.detect(decodedContent());
    }

    /** Decoding for reads only; the stored source text remains byte-for-byte unchanged. */
    public String decodedContent() {
        return io.ghx.vpn.sub.SubParser.unwrapBase64(content);
    }

    /** Explicit content markers survive refresh; old type heuristics are never trusted. */
    public String contentForRefresh(String raw) {
        String next = io.ghx.vpn.sub.SubParser.unwrapBase64(raw);
        return io.ghx.vpn.sub.SubParser.detect(decodedContent()) == TYPE_QL
                ? io.ghx.vpn.sub.QlParser.wrap(next) : next;
    }

    /** 节点数估算：直接复用真实解析器，避免与实际能力不一致。 */
    public int nodeEstimate() {
        try {
            String text = decodedContent();
            int fmt = io.ghx.vpn.sub.SubParser.detect(text);
            if (fmt == TYPE_CLASH) return -1;
            return io.ghx.vpn.sub.SubParser.parseNodes(text, fmt).size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
