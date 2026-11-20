import io.litlink.app.core.ConfigBuilder;
import io.litlink.app.sub.B64;
import io.litlink.app.sub.Node;
import io.litlink.app.sub.SubParser;

import java.util.ArrayList;
import java.util.List;

/** 纯 JDK 上的解析/配置单元测试。 */
public class T1 {

    static int pass = 0, fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ok   " + label); }
        else { fail++; System.out.println("  FAIL " + label + " -> " + detail); }
    }

    public static void main(String[] a) {
        testVmess();
        testVless();
        testTrojan();
        testSs();
        testHy2();
        testSurfboard();
        testDetect();
        testConfig();
        System.out.println();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void testVmess() {
        System.out.println("[vmess]");
        String json = "{\"v\":\"2\",\"ps\":\"🇭🇰 香港01\",\"add\":\"hk1.example.com\",\"port\":\"443\","
                + "\"id\":\"b831381d-6324-4d53-ad4f-8cda48b30811\",\"aid\":\"0\",\"scy\":\"auto\","
                + "\"net\":\"ws\",\"host\":\"cdn.example.com\",\"path\":\"/ray\",\"tls\":\"tls\",\"sni\":\"cdn.example.com\"}";
        String uri = "vmess://" + B64.encodeUtf8(json);
        Node n = io.litlink.app.sub.V2rayUriParser.parseOne(uri);
        check("解析成功", n != null, "null");
        if (n == null) return;
        check("名字含中文", n.name.contains("香港"), n.name);
        check("server", "hk1.example.com".equals(n.server), n.server);
        check("port=443", n.port == 443, "" + n.port);
        check("type=vmess", "vmess".equals(n.type), n.type);
        String y = n.toYaml();
        check("yaml 含 ws", y.contains("network: \"ws\""), y);
        check("yaml 含 tls", y.contains("tls: true"), y);
        check("yaml 含 uuid", y.contains("b831381d"), y);
        System.out.println("  " + y);
    }

    static void testVless() {
        System.out.println("[vless]");
        String uri = "vless://8f8b2f74-2b1a-4b8f-9f36-1a2b3c4d5e6f@jp.example.com:8443"
                + "?type=ws&security=tls&sni=jp.example.com&host=jp.example.com&path=%2Fws&fp=chrome#日本节点";
        Node n = io.litlink.app.sub.V2rayUriParser.parseOne(uri);
        check("解析成功", n != null, "null");
        if (n == null) return;
        check("名字", "日本节点".equals(n.name), n.name);
        check("port=8443", n.port == 8443, "" + n.port);
        String y = n.toYaml();
        check("path 解码", y.contains("\"/ws\""), y);
        System.out.println("  " + y);
    }

    static void testTrojan() {
        System.out.println("[trojan]");
        String uri = "trojan://pass%40word@sg.example.com:443?sni=sg.example.com&allowInsecure=1#新加坡";
        Node n = io.litlink.app.sub.V2rayUriParser.parseOne(uri);
        check("解析成功", n != null, "null");
        if (n == null) return;
        check("密码解码", "pass@word".equals(String.valueOf(n.opts.get("password"))), String.valueOf(n.opts.get("password")));
        System.out.println("  " + n.toYaml());
    }

    static void testSs() {
        System.out.println("[ss]");
        String userinfo = B64.encodeUtf8("aes-256-gcm:mypassword");
        String uri = "ss://" + userinfo + "@us.example.com:8388#美国SS";
        Node n = io.litlink.app.sub.V2rayUriParser.parseOne(uri);
        check("解析成功", n != null, "null");
        if (n == null) return;
        check("method", "aes-256-gcm".equals(String.valueOf(n.opts.get("cipher"))), String.valueOf(n.opts.get("cipher")));
        check("password", "mypassword".equals(String.valueOf(n.opts.get("password"))), String.valueOf(n.opts.get("password")));
        // 整体 base64 形式
        String legacy = "ss://" + B64.encodeUtf8("chacha20-ietf-poly1305:pw2@us2.example.com:8389") + "#美国SS2";
        Node n2 = io.litlink.app.sub.V2rayUriParser.parseOne(legacy);
        check("legacy 解析", n2 != null && n2.port == 8389, n2 == null ? "null" : ("" + n2.port));
        System.out.println("  " + n.toYaml());
    }

    static void testHy2() {
        System.out.println("[hysteria2]");
        String uri = "hysteria2://mypass@hk.example.com:8443?sni=hk.example.com&insecure=1#香港H2";
        Node n = io.litlink.app.sub.V2rayUriParser.parseOne(uri);
        check("解析成功", n != null, "null");
        if (n == null) return;
        check("password", "mypass".equals(String.valueOf(n.opts.get("password"))), String.valueOf(n.opts.get("password")));
        System.out.println("  " + n.toYaml());
    }

    static void testSurfboard() {
        System.out.println("[surfboard]");
        String ini = "[General]\n"
                + "loglevel = notify\n"
                + "\n"
                + "[Proxy]\n"
                + "香港01 = ss, hk.example.com, 8388, encrypt-method=aes-128-gcm, password=pw1, udp-relay=true\n"
                + "日本02 = vmess, jp.example.com, 443, username=b831381d-6324-4d53-ad4f-8cda48b30811, ws=true, ws-path=/ray, ws-headers=Host:cdn.example.com, tls=true, sni=cdn.example.com\n"
                + "美国03 = trojan, us.example.com, 443, password=pw3, sni=us.example.com\n"
                + "\n"
                + "[Proxy Group]\n"
                + "PROXY = select, 香港01, 日本02\n";
        check("识别为 surfboard", SubParser.detect(ini) == SubParser.FMT_SURFBOARD, "" + SubParser.detect(ini));
        List<Node> list = io.litlink.app.sub.SurfboardParser.parse(ini);
        check("解析出 3 个", list.size() == 3, "" + list.size());
        for (int i = 0; i < list.size(); i++) System.out.println("  " + list.get(i).toYaml());
        if (list.size() == 3) {
            check("vmess ws", list.get(1).toYaml().contains("network: \"ws\""), list.get(1).toYaml());
            check("vmess uuid", list.get(1).toYaml().contains("b831381d"), list.get(1).toYaml());
        }
    }

    static void testDetect() {
        System.out.println("[detect]");
        String clash = "proxies:\n  - {name: a, type: ss, server: x, port: 1, cipher: aes-128-gcm, password: p}\n"
                + "proxy-groups:\n  - {name: G, type: select, proxies: [a]}\nrules:\n  - MATCH,G\n";
        check("clash 识别", SubParser.detect(clash) == SubParser.FMT_CLASH, "" + SubParser.detect(clash));
        String uris = "vmess://" + B64.encodeUtf8("{\"add\":\"a.com\",\"port\":443,\"id\":\"u\",\"ps\":\"n\"}")
                + "\ntrojan://p@b.com:443#t\n";
        check("uri 识别", SubParser.detect(uris) == SubParser.FMT_URI, "" + SubParser.detect(uris));
        // 整体 base64 包裹的 URI 订阅
        String wrapped = B64.encodeUtf8(uris);
        String un = SubParser.unwrapBase64(wrapped);
        check("base64 订阅拆包", un.contains("vmess://"), un.substring(0, Math.min(30, un.length())));
    }

    static void testConfig() {
        System.out.println("[config]");
        List<Node> nodes = new ArrayList<Node>();
        nodes.add(io.litlink.app.sub.V2rayUriParser.parseOne("ss://" + B64.encodeUtf8("aes-256-gcm:pw@1.1.1.1:8388") + "#节点A"));
        nodes.add(io.litlink.app.sub.V2rayUriParser.parseOne("trojan://p@2.2.2.2:443#节点B"));

        String direct = new ConfigBuilder().mode(ConfigBuilder.MODE_DIRECT).nodes(nodes).build();
        check("直连模式 mode: direct", direct.contains("mode: direct"), "");
        check("直连模式 MATCH,DIRECT", direct.contains("MATCH,DIRECT"), "");
        check("含 proxies", direct.contains("proxies:"), "");
        check("含两个节点", direct.contains("节点A") && direct.contains("节点B"), "");

        String rule = new ConfigBuilder().mode(ConfigBuilder.MODE_RULE).nodes(nodes).build();
        check("规则模式走分组", rule.contains("MATCH," + ConfigBuilder.GROUP_NAME), "");

        String empty = new ConfigBuilder().mode(ConfigBuilder.MODE_RULE).build();
        check("无节点时回落直连", empty.contains("MATCH,DIRECT"), "");

        String base = new ConfigBuilder().mode(ConfigBuilder.MODE_DIRECT)
                .base("port: 1234\nmode: rule\nrules:\n  - DOMAIN-SUFFIX,google.com,PROXY\n  - MATCH,DIRECT\n").build();
        check("原文保留 rules", base.contains("DOMAIN-SUFFIX,google.com,PROXY"), "");
        check("覆盖原文 mode", base.contains("mode: direct") && !base.contains("mode: rule"), "");
        check("覆盖原文 port", !base.contains("port: 1234"), "");
        System.out.println("---- 直连配置样例 ----");
        System.out.println(direct);
    }
}
