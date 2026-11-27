# 轻连 LitLink

轻量级 Android 代理客户端，Clash 内核（mihomo）+ TUN 转发，支持 Clash / Surfboard / v2ray URI / ql 混合订阅。包名 `io.ghx.vpn`，Android 7.0+，arm64。

> 由 DSH 编写

## 当前版本：2.0

- **修正 1.9 未修完整的格式判定**：只有明确带 `ql://` / `ql:` 标记的内容才标为 ql。普通 v2ray URI 列表不因协议数量、分隔符、转义换行或 Base64 包装而变成 ql。
- **已有订阅无需重新导入**：每次读取按现有正文重算格式，不再相信旧版本保存的错误类型；读取时不写回磁盘，不改订阅正文和设置，不联网。
- 导入预览明确显示「v2ray URI 列表」；节点拆分能力保留，不把标签更正当成节点转换。
- 保留网速曲线、上传/下载速度、活跃连接和累计流量；本次不修改统计或 VPN 行为。
- [完整更新与验证范围](RELEASE-2.0.md)

## 功能

- 订阅管理：多订阅、手动刷新、按分钟自动更新（最短 15 分钟）。
- 首页保留大号「管理订阅」入口；订阅页提供「＋ 添加订阅」和「导入」。
- 导入支持文件、链接、粘贴文本；URL 导入保留订阅地址供以后更新。
- 格式支持 Clash YAML、Surfboard conf、v2ray URI/Base64 列表及显式 ql 包装。
- 节点列表、选择记忆、延迟测速与排序；规则/全局/直连模式及分应用绕过。
- 中文/English、跟随系统/浅色/深色、Material You、自定义配色。
- DNS/IP/速度等诊断工具；不把本机探测成功当成其他应用 VPN 通路已验证。
- 开机/升级接收器只检查到期订阅，不自动开启 VPN。统计面板也不启动或重连 VPN。

## ql 与普通 URI 列表

只有明确的 `ql://` / `ql:` 包装才提供 ql 格式证据。无标记的 URI 列表，即使同一行多个链接、协议混合、使用空格/逗号/分号/竖线/转义换行/首尾相连，或外套一至两层 Base64，也仍是 **v2ray**。

节点拆分器由两种格式共用，调用拆分器并不表示内容就是 ql。原有节点解析保持不变。旧配置的类型仅在读取时按正文校正，不自动写回磁盘或改动正文。

入口：管理订阅 → 导入 → 粘贴文本 / 从文件导入 / 从链接导入。导入只保存配置，不自动建立 VPN。

## 构建与测试

本项目在 Android 设备上构建，无需 Gradle。工具链位于 `/data/local/tmp/tc`（aapt2、Android SDK jar、OpenJDK 17、D8、apksigner）；arm64 原生库位于 `/sdcard/llb/libs/arm64-v8a`。源码包不包含工具链、原生二进制、用户配置或 GitHub 凭据。

```sh
sh tools/test-offline.sh
node tools/testsrc/AuditTunContract.js
sh tools/build.sh
```

设备上使用 shell 用户在 `/data/local/tmp` 下运行 JVM 测试与构建；Node 源码契约检查可在有 Node 的环境运行。

2.0 已通过新增的 602 项格式回归检查（同一套测试在 1.9 中失败 180 项）和 2016 项旧配置格式/数据保持检查，以及配置、订阅、导入、外观、测速、更新间隔、统计、TUN 状态的完整离线测试及 84 项源码契约检查。输入为合成数据，统计使用模拟 HTTP；没有在这些测试中执行 JNI、SelfTest、联网或 VPN。

**本次未开启 VPN，因此未做连接态端到端验证。** 离线测试通过不代表真实 VPN 流量已验证。

目录：
- `app/src/main/java/io/ghx/vpn`：界面、订阅/配置、内核会话、网络工具。
- `app/src/main/java/io/ghx/vpn/core/TrafficStats.java`：统计解码、本机鉴权端点与有界读取。
- `app/src/main/java/io/ghx/vpn/ui/TrafficPanel.java`：首页统计界面。
- `tools/testsrc`：离线回归测试；`tools/build.sh`：设备端构建。

## 下载

[2.0 发行版](https://github.com/guhaoxin123/qinglian/releases/tag/v2.0)：
- `qinglian_2.0.apk`
- `qinglian_2.0-source.tgz`

使用原签名覆盖升级，不需卸载或清理数据。

## 说明

仅供学习与自用，请遵守当地法律法规。本项目不提供任何节点或订阅。
