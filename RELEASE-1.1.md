# 轻连 1.1

Display name: 轻连 (package io.ghx.vpn unchanged, versionName 1.1, versionCode 2). The launcher label, in-app title, settings preview, About version row, VPN notification title/session and generated group-name suffix now use the new name. Internal log tags, HTTP User-Agent and resource/style identifiers keep their original ASCII names for compatibility.

Android 7.0+ (minSdk 24, targetSdk 35), arm64-v8a. Native core/JNI and previously fixed TUN IPv4 /30 contract are unchanged.

## New features

- App language: follow system, Simplified Chinese, English. Unsupported system languages fall back to Chinese. User-provided node/profile/application names and external diagnostics are not translated.
- Appearance: follow system, light, dark. Android 12+ uses public system wallpaper accent resources when Material You is enabled; older Android keeps the manual accent.
- Custom palette: hue/saturation wheel with accessible sliders, brightness, RGB tab, Hex (#RRGGBB), live preview, reset, cancel, confirm. Only Confirm saves. Custom confirmation turns wallpaper accent off. Text contrast is derived from the selected fill color.
- Real system document picker (Storage Access Framework), with no broad storage permission. Existing Add subscription and separate Paste import remain available, as does the homepage Manage subscriptions shortcut.
- Import preview before saving. Reader closes streams, bounds raw/decoded content to 8 MiB, supports UTF-8 and BOM-marked UTF-16, rejects archives/binary/invalid encodings/empty content. Basic format/node inspection only: advanced YAML and providers are not fully validated and no remote provider is fetched during inspection. Confirm saves a profile but does not explicitly select it, reload the core, or start VPN. Existing first-enabled-profile fallback still applies if no active profile exists.
- Language/appearance preferences use the separate litlink_appearance file; proxy/subscription preferences are not reset.

## Build and offline tests

The on-phone build requires `/data/local/tmp/tc` (JDK 17, android.jar, aapt2, d8.jar, apksigner.jar), and precompiled native libraries under `/sdcard/llb/libs/arm64-v8a`.

Run `sh tools/build.sh` as the Android shell user in a fresh project staging directory. The original local signing identity is used for in-place upgrades. No private keystore is distributed in this source archive. `tools/build.sh` expects the existing local development keystore; producing the same update signature elsewhere requires the matching private key.

Run `sh tools/test-offline.sh` in that staging directory for synthetic JVM tests. `node tools/testsrc/AuditTunContract.js` checks source/API patterns only. Neither these tests nor UI verification should start VPN or invoke the destructive developer `SelfTest` class.

This archive contains app/tools Java and resources, not native core source, precompiled .so files, or the build toolchain. An APK build and static/unit tests do not establish live VPN/network correctness. The current feature verification intentionally does not enable VPN.
