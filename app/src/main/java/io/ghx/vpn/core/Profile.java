package io.ghx.vpn.core;

/** 一份节点配置：可来自订阅 URL，也可以是手动粘贴。 */
public final class Profile {

    /** 还没有下载成功：只有地址、没有内容，格式未知。 */
    public static final int TYPE_UNKNOWN = 0;
    public static final int TYPE_CLASH = 1;
    public static final int TYPE_URI = 2;
    public static final int TYPE_SURFBOARD = 3;
    /** ql 混合：一条内容里混排多个不同协议的节点链接。 */
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

    /** 节点数估算：直接复用真实解析器，避免与实际能力不一致。 */
    public int nodeEstimate() {
        if (content == null || content.length() == 0) return 0;
        if (type == TYPE_CLASH) return -1;
        try {
            int fmt = io.ghx.vpn.sub.SubParser.detect(content);
            return io.ghx.vpn.sub.SubParser.parseNodes(content, fmt).size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
