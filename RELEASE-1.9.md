# 轻连 1.9

## 修复

- 网速曲线和累计上传/下载流量改为直接读取内核 JNI 计数器，正确解码其压缩数值及单位，不再依赖写死的 9090 控制接口，也不从有精度损失的累计值差分计算网速。
- 活跃连接数通过真正的 REST 控制接口查询：内核装载配置时，通过 session override 设置本机空闲端口与随机口令。CMFA 会移除 YAML 中的控制器地址；`nativeStartHttp` 是转发代理，并不是控制器。
- 查询使用本机直连、鉴权、超时/大小上限及取消机制。连接数接口失败时单独显示不可用，不影响已经成功读到的网速和流量。
- 订阅格式标签按已识别的格式显示；普通多协议 v2ray/URI 列表不再被误标为「ql 混合」。不改订阅内容和节点解析行为。
- 保留订阅、当前节点、全局模式默认值、首页管理入口，以及文件/链接/粘贴导入。

## 安全与验证范围

- 统计面板只读取已运行内核，不初始化、加载、启动或重连 VPN。
- 使用纯 Java 计数器解码、格式标签、模拟 HTTP 响应与生命周期回归测试；没有执行 JNI、SelfTest 或 VPN 连接测试。
- 尚未做连接状态下的端到端验证；不能将离线测试通过等同于已验证真实 VPN 流量。
- 1.9 / versionCode 19，使用原签名覆盖升级，不卸载、不清理数据。

## 实现依据

- [CMFA controller stripping and override order](https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/core/src/main/golang/native/config/process.go)
- [CMFA forward HTTP proxy](https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/core/src/main/golang/native/proxy/http.go)
- [CMFA native traffic counters](https://github.com/MetaCubeX/ClashMetaForAndroid/blob/main/core/src/main/golang/native/tunnel.go)

