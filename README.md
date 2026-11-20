# LitLink

轻量级 Android 代理客户端，Clash 内核（mihomo）+ TUN 转发，支持 **Clash / Surfboard / v2rayNG** 三种订阅格式。

> 由 DSH 编写

## 功能

- **订阅管理**：多订阅并存、自动定时更新、手动导入文件或链接
- **订阅格式**：Clash YAML、Surfboard `.conf`、v2rayNG 分享链接 / Base64 订阅，三端通用
- **节点**：分组展示、按地区归类、延迟测速与排序、自动挑选最优节点
- **TUN 模式**：全局 VPN 转发（`TunnelService`），开机自启（`BootReceiver`）
- **分流**：规则过滤（`RuleFilter`）、按应用绕过（`BypassActivity`）
- **诊断**：自检（`SelfTest`）、DNS 检查、IP 信息、测速、日志面板
- **界面**：原生 Activity，无第三方 UI 框架，体积小、启动快

## 目录结构

```
app/src/main/
├── AndroidManifest.xml          包名 io.ghx.vpn，minSdk 24 / targetSdk 35
├── java/io/ghx/vpn/
│   ├── MainActivity.java        主界面：连接开关、状态、流量
│   ├── NodesActivity.java       节点列表 / 测速 / 选择
│   ├── SubsActivity.java        订阅管理与导入
│   ├── SettingsActivity.java    设置项
│   ├── BypassActivity.java      分应用代理
│   ├── core/                    配置构建、订阅、会话、延迟、自检、日志
│   ├── net/                     下载、DNS、IP 信息、测速
│   ├── sub/                     Clash / Surfboard / v2rayNG 解析器
│   ├── svc/                     TunnelService、BootReceiver、TUN 回调
│   └── ui/                      格式化与界面工具
└── res/                         图标与资源
tools/
├── build.sh                     设备端构建脚本（aapt2 + javac + d8）
├── ZipPack.java                 打包 APK
├── Shrink.java                  资源/代码裁剪
└── testsrc/                     配置、测速、订阅解析的自测
```

## 构建

本项目在 Android 设备上直接构建（无需 Gradle），依赖放在 `/data/local/tmp/tc`：

```
sh tools/build.sh
```

脚本流程：`aapt2 compile` → `aapt2 link` → `javac`（bootclasspath 用 `android.jar`）→ `d8` 转 dex → 组装并签名产出 APK。

## 下载

APK 见 [Releases](../../releases/latest)：`LitLink_1.0.0.apk`

## 说明

仅供学习与自用，请遵守当地法律法规。本项目不提供任何节点或订阅。
