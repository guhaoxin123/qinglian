# 轻连 LitLink

轻量级 Android 代理客户端，Clash 内核（mihomo）+ TUN 转发，支持 Clash / Surfboard / v2ray URI / ql 混合订阅。包名 `io.ghx.vpn`，Android 7.0+，arm64。

> 由 DSH 编写

## 当前版本：1.9

- 首页提供网速曲线、上传/下载速度、活跃连接数与内核累计流量。
- 网速及累计流量直接查询 JNI 计数器；连接数通过 session override 启用的本机鉴权 REST 接口读取。控制器异常不再连带隐藏正常的内核流量。
- **格式不等于协议**：普通按行排列的 vmess / vless / trojan / ss 等 URI 列表仍标为 v2ray，不因含多个协议就显示 ql。只有已识别为 ql 的内容才标「ql 混合」；未下载成功显示「待识别」。
- [完整更新与验证范围](RELEASE-1.9.md)

## 功能

- 订阅管理：多订阅、手动刷新、按分钟自动更新（最短 15 分钟）。
- 首页保留大号「管理订阅」入口；订阅页提供「＋ 添加订阅」和「导入」。
- 导入支持文件、链接、粘贴文本；URL 导入保留订阅地址供以后更新。
- 格式支持 Clash YAML、Surfboard conf、v2ray URI/Base64 列表及 ql 包装/同一行混排链接。
- 节点列表、选择记忆、延迟测速与排序；规则/全局/直连模式及分应用绕过。
- 中文/English、跟随系统/浅色/深色、Material You、自定义配色。
- DNS/IP/速度等诊断工具；不把本机探测成功当成其他应用 VPN 通路已验证。
- 开机/升级接收器只检查到期订阅，不自动开启 VPN。统计面板也不启动或重连 VPN。

## ql 与普通 URI 列表

显式 `ql://` 包装，或同一行混排多个不同协议链接，可以由 ql 解析器拆分。空格、逗号、分号、竖线及首尾相连等写法有相应支持。

**每行一个链接的普通多协议列表仍属于 URI/v2ray 格式**；节点协议种类与格式标签分开处理。此次修正不改变既有解析规则或用户订阅内容。

入口：管理订阅 → 导入 → 粘贴文本 / 从文件导入 / 从链接导入。导入只保存配置，不自动建立 VPN。

## 构建与测试

本项目在 Android 设备上构建，无需 Gradle。工具链位于 `/data/local/tmp/tc`（aapt2、Android SDK jar、OpenJDK 17、D8、apksigner）；arm64 原生库位于 `/sdcard/llb/libs/arm64-v8a`。源码包不包含工具链、原生二进制、用户配置或 GitHub 凭据。

```sh
sh tools/test-offline.sh
node tools/testsrc/AuditTunContract.js
sh tools/build.sh
```

设备上使用 shell 用户在 `/data/local/tmp` 下运行 JVM 测试与构建；Node 源码契约检查可在有 Node 的环境运行。

1.9 已通过：配置 107、订阅 53、文件导入 142、外观 20516、离线测速 225、更新间隔 40、统计 64、TUN 状态 14 项测试及 77 项源码契约检查。统计测试使用合成数值与模拟 HTTP，没有执行 JNI、SelfTest、网络连接或 VPN。

**本次未开启 VPN，因此未做连接态端到端验证。** 离线测试通过不代表真实 VPN 流量已验证。

目录：
- `app/src/main/java/io/ghx/vpn`：界面、订阅/配置、内核会话、网络工具。
- `app/src/main/java/io/ghx/vpn/core/TrafficStats.java`：统计解码、本机鉴权端点与有界读取。
- `app/src/main/java/io/ghx/vpn/ui/TrafficPanel.java`：首页统计界面。
- `tools/testsrc`：离线回归测试；`tools/build.sh`：设备端构建。

## 下载

[1.9 发行版](https://github.com/guhaoxin123/qinglian/releases/tag/v1.9)：
- `qinglian_1.9.apk`
- `qinglian_1.9-source.tgz`

使用原签名覆盖升级，不需卸载或清理数据。

## 说明

仅供学习与自用，请遵守当地法律法规。本项目不提供任何节点或订阅。
