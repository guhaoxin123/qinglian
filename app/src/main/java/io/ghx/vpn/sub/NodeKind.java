package io.ghx.vpn.sub;

import java.util.List;
import java.util.Locale;

/**
 * 从一个配置真正解析出来的节点里概括协议：全是同一种协议就返回它，
 * 混了多种协议就返回 null。协议摘要不能用来判定订阅格式或 ql 标签。
 */
public final class NodeKind {

    private NodeKind() {
    }

    /** 全部节点同一种协议时返回小写协议名；空列表或混合协议返回 null。 */
    public static String uniform(List<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        String only = null;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            String type = n == null || n.type == null ? "" : n.type.trim().toLowerCase(Locale.ROOT);
            if (only == null) {
                if (type.length() == 0) return null;
                only = type;
            } else if (!only.equals(type)) {
                return null;
            }
        }
        return only;
    }
}
