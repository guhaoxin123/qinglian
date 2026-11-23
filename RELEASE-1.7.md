# 轻连 1.7

Display name: 轻连 (package io.ghx.vpn unchanged, versionName 1.7, versionCode 17). The launcher label, in-app title, settings preview, About version row, VPN notification title/session and generated group-name suffix use the new name. Internal log tags, HTTP User-Agent and resource/style identifiers keep their original ASCII names for compatibility.

Android 7.0+ (minSdk 24, targetSdk 35), arm64-v8a. Native core/JNI and previously fixed TUN IPv4 /30 contract are unchanged.

## New features

- Two buttons only: the profile screen shows just 「＋ 添加订阅」 and 「导入」. Import opens a small chooser (file / link / paste text); all three share one reader, one format detector and one review-before-save dialog, and all accept the four supported formats (Clash, Surfboard, v2ray links, ql mixed). The picker stays `*/*`, so `.yaml`, `.yml`, `.conf` and `.txt` all remain selectable.
- Auto-update intervals are now in minutes with a 15-minute floor: 关闭 / 15 分钟 / 30 分钟 / 1 / 3 / 6 / 12 / 24 小时, cycled by tapping the card's interval chip and by the Settings default. Card labels show short values as `15分` / `30分`. Existing installs keep their setting: a stored hour value is converted once (6 h → 360 min) and later reads use the minute key.
- Short intervals actually fire: previously a check ran only when the app opened or after a reboot, so anything below a few hours would have been missed. A `SubCheckReceiver` broadcast is now scheduled with `AlarmManager.setAndAllowWhileIdle` for the earliest due subscription and re-armed after each batch; it needs no exact-alarm permission, survives reboots through the existing boot receiver, and is `exported="false"`. It only refreshes subscriptions — it never selects a profile or starts the VPN.
- Version numbering from 1.5 on: every change bumps the minor number by 0.1 (1.5 → 1.6 → 1.7 …), with versionCode equal to versionName × 10 (15, 16, 17 …).

- Import from link (auto-updating since 1.4): the link is fetched over http(s), previewed with the same validation as file and paste import, then saved as a subscription that keeps its address and refreshes on the normal auto-update schedule (default every 12 h, configurable or disabled in Settings). The reviewed download is stored as-is, so no second request is made. Still no profile switch, no core reload and no VPN start; redirects are followed (max 6), content is bounded to 8 MiB, and non-http(s) schemes, spaces or over-long links are rejected before any connection.
- ql mixed format: one text may hold two, three or more node links of different protocols (vmess / vless / trojan / ss / hysteria2 / tuic / socks), separated by nothing at all or by spaces, commas, semicolons, pipes or escaped newlines. Each link is split out and parsed by the existing per-protocol parsers; an optional `ql://` prefix wraps either the plain mix or a base64 of it. A plain one-link-per-line list is still reported as an ordinary link list.

## Fixes in 1.6 (empty node list on a fresh subscription)
- A subscription added by link used to be stored with the format `v2ray` before anything had been downloaded, so a ql subscription showed a `v2ray` badge while the node screen stayed empty. There is now an explicit unknown format (`TYPE_UNKNOWN`, saved as `type=0`) used until the first successful download decides the real one; 1.5-era profiles that have a URL but no content are migrated on load.
- The profile card no longer hides a failed download behind 「尚未更新」: a not-yet-downloaded profile reads 「尚未更新 · 点「刷新」下载」, and a failed one keeps showing 「错误：…」 in red.
- The node screen distinguishes “no nodes because the download never succeeded” (「这个订阅还没下载成功，回到配置页点「刷新」」) from “nothing parsed in this configuration”, instead of always blaming the parser.
- Download errors are explained instead of surfacing the raw Java text: timeout (20 s), DNS failure, HTTPS handshake failure and refused connection each get a Chinese/English sentence, so a `timeout` in the diagnostic log is now readable in the app.

## Fix in 1.7 (the badge says v2ray for a ql subscription)

- The format badge on a profile card was taken from the stored profile type, which for any one-link-per-line list is the generic “link list” type, labelled `v2ray`. A ql subscription with 19 mixed nodes therefore showed 「ql」 + 「v2ray」 on the same card. The badge is now summarised from the protocols that were actually parsed (new pure helper `NodeKind.uniform`): one protocol shows its own name (`vmess` / `trojan` / `ss` / `hysteria2` / `tuic` / `socks5`), several protocols show 「ql 混合」, an unfetched profile still shows 「待识别」. Storage format, node parsing, node counts and the kernel configuration are unchanged.
- The import review dialog calls a link list 「节点链接」 instead of 「v2ray 链接」 for the same reason.

- App language: follow system, Simplified Chinese, English. Unsupported system languages fall back to Chinese. User-provided node/profile/application names and external diagnostics are not translated.
- Appearance: follow system, light, dark. Android 12+ uses public system wallpaper accent resources when Material You is enabled; older Android keeps the manual accent.
- Custom palette: hue/saturation wheel with accessible sliders, brightness, RGB tab, Hex (#RRGGBB), live preview, reset, cancel, confirm. Only Confirm saves. Custom confirmation turns wallpaper accent off. Text contrast is derived from the selected fill color.
- Real system document picker (Storage Access Framework), with no broad storage permission. Add subscription and the single Import entry (file / link / paste) remain available, as does the homepage Manage subscriptions shortcut.
- Import preview before saving. Reader closes streams, bounds raw/decoded content to 8 MiB, supports UTF-8 and BOM-marked UTF-16, rejects archives/binary/invalid encodings/empty content. Basic format/node inspection only: advanced YAML and providers are not fully validated and no remote provider is fetched during inspection. Confirm saves a profile but does not explicitly select it, reload the core, or start VPN. Existing first-enabled-profile fallback still applies if no active profile exists.
- Language/appearance preferences use the separate litlink_appearance file; proxy/subscription preferences are not reset.

## Build and offline tests

The on-phone build requires `/data/local/tmp/tc` (JDK 17, android.jar, aapt2, d8.jar, apksigner.jar), and precompiled native libraries under `/sdcard/llb/libs/arm64-v8a`.

Run `sh tools/build.sh` as the Android shell user in a fresh project staging directory. The original local signing identity is used for in-place upgrades. No private keystore is distributed in this source archive. `tools/build.sh` expects the existing local development keystore; producing the same update signature elsewhere requires the matching private key.

Run `sh tools/test-offline.sh` in that staging directory for synthetic JVM tests. `node tools/testsrc/AuditTunContract.js` checks source/API patterns only. Neither these tests nor UI verification should start VPN or invoke the destructive developer `SelfTest` class.

This archive contains app/tools Java and resources, not native core source, precompiled .so files, or the build toolchain. An APK build and static/unit tests do not establish live VPN/network correctness. The current feature verification intentionally does not enable VPN.
