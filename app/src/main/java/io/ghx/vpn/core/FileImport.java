package io.ghx.vpn.core;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 文件导入：把订阅内容（Clash YAML / 节点 URI 列表 / Surfboard）粘进文本文件即可导入。
 * 扫描应用外部目录下的 import.txt，导入成功后改名为 import.done 防止重复。
 */
public final class FileImport {

    public static final String NAME = "import.txt";
    public static final String DONE = "import.done";

    private FileImport() {
    }

    /** 返回导入的订阅；没有可导入文件时返回 null。 */
    public static Profile runOnce(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return null;
            File src = new File(dir, NAME);
            if (!src.exists() || src.length() == 0) return null;

            String content = read(src);
            if (content == null || content.trim().length() == 0) return null;

            SubscriptionManager sm = new SubscriptionManager(ctx);
            String name = guessName(content);
            Profile p = sm.addRaw(name, content, new Prefs(ctx).autoUpdateHours());
            src.renameTo(new File(dir, DONE));
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String guessName(String content) {
        String t = content.trim();
        if (t.startsWith("proxies:") || t.contains("\nproxies:")) return "导入配置";
        List<io.ghx.vpn.sub.Node> nodes;
        try {
            nodes = io.ghx.vpn.sub.SubParser.parseNodes(t, io.ghx.vpn.sub.SubParser.detect(t));
        } catch (Throwable e) {
            return "导入配置";
        }
        if (nodes.isEmpty()) return "导入配置";
        return "导入 " + nodes.size() + " 个节点";
    }

    private static String read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
