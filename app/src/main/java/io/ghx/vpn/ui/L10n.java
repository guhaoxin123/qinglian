package io.ghx.vpn.ui;

import android.content.Context;

/** Small explicit bilingual layer for programmatic screens and service messages. */
public final class L10n {
    private L10n() { }

    public static boolean isEnglish(Context context) {
        String mode = Appearance.language(context);
        return "en".equals(mode) || ("system".equals(mode)
                && "en".equalsIgnoreCase(Appearance.systemLocale().getLanguage()));
    }

    public static String t(Context context, String zh, String en) {
        return isEnglish(context) ? en : zh;
    }

    public static String error(Context context, Throwable error) {
        if (error == null) return t(context, "未知错误", "Unknown error");
        String detail = error.getMessage();
        if (detail == null || detail.trim().length() == 0) detail = error.getClass().getSimpleName();
        return message(context, detail);
    }

    /** Known app-generated messages only. Unknown/native diagnostics are preserved verbatim. */
    public static String message(Context context, String detail) {
        if (detail == null || detail.length() == 0) return t(context, "未知错误", "Unknown error");
        if (!isEnglish(context)) return detail;
        // Translate only recognized app-generated prefixes, never arbitrary diagnostic bodies.
        if (detail.startsWith("有效下载数据不足: "))
            return "Not enough valid download data: " + detail.substring("有效下载数据不足: ".length());
        if (detail.startsWith("下载未完成: "))
            return "Download incomplete: " + detail.substring("下载未完成: ".length());
        if (detail.startsWith("测速中断: "))
            return "Speed test interrupted: " + detail.substring("测速中断: ".length());
        if (detail.startsWith("内核装载失败: "))
            return "Could not load the core: " + detail.substring("内核装载失败: ".length());
        switch (detail) {
            case "已取消": return "Cancelled";
            case "上一连接正在停止，请稍后重试": return "The previous connection is stopping; try again later";
            case "还没有节点，请先添加或选用一个配置": return "No nodes yet; add or select a profile first";
            case "系统未授予 VPN 权限，请重新连接并在弹窗点「允许」":
                return "VPN permission was not granted; connect again and tap Allow in the system dialog";
            case "写入运行配置失败": return "Could not write the runtime configuration";
            case "配置已删除": return "The profile was deleted";
            case "有效下载数据不足": return "Not enough valid download data";
            case "下载连接未及时停止，已停止后续测速": return "The download connection did not stop in time; further testing was cancelled";
            case "不支持的 URL 协议": return "Unsupported URL protocol";
            case "已到测速期限": return "The speed-test time limit was reached";
            case "空响应体": return "Empty response body";
            case "无效的响应读取长度": return "Invalid response read length";
            case "响应体没有读取进展": return "No progress reading the response body";
            case "无效 Content-Length": return "Invalid Content-Length";
            case "无效 Content-Range": return "Invalid Content-Range";
            case "Content-Range 与请求或长度不匹配": return "Content-Range does not match the request or length";
            case "响应内容不是二进制测速数据": return "The response is not binary speed-test data";
            case "没有订阅地址": return "No subscription URL";
            case "订阅内容为空": return "The subscription is empty";
            case "无法识别的订阅格式": return "Unrecognized subscription format";
            case "订阅没有可解析的节点": return "No supported nodes found in the subscription";
            case "返回的是网页，可能订阅地址失效或需要 UA":
                return "The server returned a web page; the URL may have expired or require a User-Agent";
            case "读取配置失败，原文件未修改": return "Could not read the profile; the original file was not changed";
            case "配置文件损坏，原文件未修改": return "The profile is damaged; the original file was not changed";
            case "保存配置失败，原文件未修改": return "Could not save the profile; the original file was not changed";
            case "配置缺少 id": return "The profile has no ID";
            case "无法创建配置目录": return "Could not create the profile directory";
            case "无法替换配置文件": return "Could not replace the profile file";
            case "上次延迟检测仍未结束，请稍后重试": return "The previous latency test is still running; try again later";
            case "上次配置装载仍未结束，请稍后重试": return "The previous profile load is still running; try again later";
            case "配置载入超时": return "Profile loading timed out";
            case "无法应用节点选择，请检查配置分组": return "Could not apply the selected node; check the profile's proxy groups";
            case "内核拒绝 TUN 网段地址": return "The core rejected the TUN subnet address";
            case "内核报告 TUN 启动/运行失败": return "The core reported a TUN startup/runtime failure";
            case "跳转缺少 Location": return "The redirect has no Location header";
            case "重定向次数过多": return "Too many redirects";
            case "订阅过大（超过 8MB）": return "The subscription exceeds the 8 MB limit";
            case "无效的测速参数": return "Invalid speed-test parameters";
            case "下载连接未能在关闭期限内退出": return "The download connection did not close before the deadline";
            case "测速已取消": return "Speed test cancelled";
            default: return detail;
        }
    }
}
