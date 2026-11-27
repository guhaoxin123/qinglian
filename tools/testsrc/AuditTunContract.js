'use strict';
// Offline source/API contract assertions: never launch an app, native core or VPN.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '../..');
const src = n => fs.readFileSync(path.join(root, 'app/src/main/java', n), 'utf8');
const tun = src('io/ghx/vpn/svc/TunnelService.java');
const cfg = src('io/ghx/vpn/core/ConfigBuilder.java');
const session = src('io/ghx/vpn/core/Session.java');
const main = src('io/ghx/vpn/MainActivity.java');
const status = src('io/ghx/vpn/core/CoreTunStatus.java');
let count = 0;
function check(ok, name) { if (!ok) throw new Error(name); count++; }
check(/TUN_PREFIX4\s*=\s*30/.test(tun), 'IPv4 prefix must match /30');
check(/TUN_GATEWAY4\s*=\s*TUN_ADDR4\s*\+\s*"\/"\s*\+\s*TUN_PREFIX4/.test(tun), 'native gateway must be CIDR');
check(/b\.addAddress\(TUN_ADDR4, TUN_PREFIX4\)/.test(tun), 'Builder/native subnet consistency');
check(/nativeStartTun\(\s*nativeFd, "gvisor", TUN_GATEWAY4, TUN_DNS, TUN_DNS,/.test(tun), 'JNI gateway/portal/DNS contract');
check(!/b\.addAddress\(TUN_ADDR6/.test(tun) && !/b\.addRoute\("::"/.test(tun), 'no unsupported IPv6 route');
check(!/b\.allowFamily\(/.test(tun), 'no IPv6 leak/bypass');
check(cfg.includes('ipv6: false'), 'core remains IPv4 only');
check(tun.includes('b.addDnsServer(TUN_DNS)'), 'virtual DNS preserved');
check(tun.includes('b.setBlocking(false)'), 'nonblocking TUN');
check(tun.includes('tunFd.detachFd()'), 'native FD ownership transfer');
check(!tun.includes('Bridge.INSTANCE.nativeStartHttp('), 'do not bind duplicate HTTP listener on mixed-port');
check(session.includes('CoreTunStatus.install()'), 'native failure observation installed');
check(tun.includes('CoreTunStatus.begin()') && tun.includes('CoreTunStatus.end()'), 'failure observation lifecycle');
check((tun.match(/CoreTunStatus\.error\(\)/g)||[]).length >= 2, 'check immediate and async errors');
check(status.includes('time < startedAt'), 'ignore stale native logs');
check(!status.includes('Log.i(message)') && !status.includes('Log.e(message'), 'no raw core logs persisted');
check(main.includes('不代表其他应用的 VPN 通路已验证'), 'accurate network check scope');
check(tun.includes('START_NOT_STICKY'), 'no automatic VPN restart');

// 2026-11-20 second review: lifecycle / hang guards.
const dns = src('io/ghx/vpn/net/DnsCheck.java');
check(dns.includes('worker.join('), 'system DNS resolution must be time-bounded');
check(dns.indexOf('systemResolve(timeoutMs)') > 0, 'bounded resolver actually used');
check(main.includes('unregisterNetworkCallback()'), 'network callback registered idempotently');
check((main.match(/unregisterNetworkCallback\(\)/g) || []).length >= 3, 'unregister helper called by resume and stop');
check(main.indexOf('unregisterNetworkCallback();\n        networkCallback = new') > 0, 'old callback removed before re-register');
check(session.includes('String latest = prefs.selectedNode()'), 'restore keeps newest user selection');
check(session.includes('synchronized (SELECTION_LOCK)'), 'selection writeback is mutually exclusive with select');
check(session.includes('requireIdle'), 'idle guard before rewriting active config');
const net = src('io/ghx/vpn/net/Net.java');
check(net.includes('code < 200 || code >= 300'), 'HTTP error bodies rejected');
const ip = src('io/ghx/vpn/net/IpInfo.java');
check((ip.match(/if \(i\.ip\.length\(\) == 0\) return null;/g) || []).length >= 3, 'every IP source validates payload');

// 2026-11-23: URL import must store the address so the ordinary scheduler keeps it fresh.
const subs = src('io/ghx/vpn/SubsActivity.java');
const mgr = src('io/ghx/vpn/core/SubscriptionManager.java');
check(mgr.includes('public Profile addRemote('), 'URL import persists the subscription address');
check(mgr.includes("p.autoUpdateMinutes = Interval.normalize(autoUpdateMinutes);")
        || /addRemote[\s\S]{0,900}autoUpdateMinutes = Interval\.normalize\(autoUpdateMinutes\)/.test(mgr),
        'URL import honours the auto-update interval');
check(subs.includes('manager.addRemote(name, url, text, autoUpdateMinutes)'), 'URL import routed through addRemote');
check(subs.includes('manager.addRaw(name, text, 0)'), 'file and paste import stay one-shot');
check(!/addRemote[\s\S]{0,400}(select\(|TunnelService|Session\.)/.test(mgr), 'saving a URL import never selects or starts the VPN');
const manifest = fs.readFileSync(path.join(root, 'app/src/main/AndroidManifest.xml'), 'utf8');
check(manifest.includes("android:versionName=\"2.0\""), "versionName is 2.0");
check(manifest.includes("android:versionCode=\"20\""), "versionCode is 20");
const traffic = src('io/ghx/vpn/ui/TrafficPanel.java');
const stats = src('io/ghx/vpn/core/TrafficStats.java');
check(stats.includes('Proxy.NO_PROXY'), 'stats bypass proxy');
check(traffic.includes('Session.trafficEpoch()'), 'stats reject stale sessions');
check(traffic.includes('g!=generation'), 'stats reject paused requests');
check(traffic.includes('Session.queryTraffic(epoch)') && !traffic.includes('lastUp'), 'native rates do not amplify rounded cumulative-counter deltas');
check(stats.includes('setReadTimeout(1200)'), 'stats reads time out');
check(!traffic.includes('Session.ensure(') && !traffic.includes('nativeStart'), 'dashboard never starts core');
check(!session.includes('nativeStartHttp('), 'forward proxy must never be mistaken for statistics controller');
check(session.includes('nativeQueryTrafficNow()') && session.includes('nativeQueryTrafficTotal()'), 'primary statistics use native counters');
check(session.includes('CORE_LOCK.tryLock(200,'), 'native statistics lock is bounded');
check(session.includes('nativeReadOverride(1)') && session.includes('nativeWriteOverride(1,'), 'REST enabled through actual session override slot');
check(session.indexOf('prepareStatistics();') < session.indexOf('Bridge.INSTANCE.nativeLoad('), 'controller override precedes native load');
check(stats.includes('127.0.0.1:') && stats.includes('setRequestProperty("Authorization"'), 'REST is authenticated and loopback-only');
check(stats.includes('setInstanceFollowRedirects(false)'), 'auth token cannot follow redirects');
check(traffic.includes('reader.cancel()') && traffic.includes('w.interrupt()'), 'paused panel cancels pending work');
check(traffic.includes('内核流量正常 · 连接数接口不可用'), 'REST failure does not blank working native counters');
check(traffic.includes('Exception | LinkageError'), 'unavailable JNI does not strand busy state');

// 2026-11-23: the profile screen keeps exactly two buttons; one Import entry serves every source.
check((subs.match(/fillBtn\(/g) || []).length === 3, 'only two buttons plus the factory');
check(subs.includes('private void importDialog()'), 'Import opens a source chooser');
check(!subs.includes('"导入文件"') && !subs.includes('"从 URL 导入"') && !subs.includes('"粘贴导入"'),
        'no leftover per-source buttons');
check(subs.includes('"从文件导入"') && subs.includes('"从链接导入"') && subs.includes('"粘贴文本"'),
        'chooser offers file, link and paste');
check(subs.includes('picker.setType("*/*")'), 'file picker accepts yaml/conf/txt');

// 2026-11-23: auto-update is measured in minutes with a 15-minute floor, and it wakes itself up.
const interval = src('io/ghx/vpn/core/Interval.java');
const auto = src('io/ghx/vpn/core/AutoUpdate.java');
const prefs = src('io/ghx/vpn/core/Prefs.java');
check(interval.includes('public static final int MIN_MINUTES = 15;'), '15 minutes is the floor');
check(interval.includes('{0, 15, 30, 60, 180, 360, 720, 1440}'), 'interval ladder starts at 15 minutes');
check(!/autoUpdateHours\b/.test(subs), 'profile screen no longer talks in hours');
check(prefs.includes('public int autoUpdateMinutes()') && !prefs.includes('public int autoUpdateHours()'),
        'preference is stored in minutes');
check(prefs.includes('K_AUTO_HOURS') && prefs.includes('sp.getInt(K_AUTO_HOURS, 0) * 60'),
        'existing 1.4 hour settings migrate to minutes');
check(auto.includes('setAndAllowWhileIdle'), 'short intervals get an alarm, not only app-open checks');
check(manifest.includes('.svc.SubCheckReceiver') && manifest.includes('io.ghx.vpn.action.SUB_CHECK'),
        'scheduled check is registered');
check(!/SubCheckReceiver[\s\S]{0,200}android:exported="true"/.test(manifest), 'scheduled check stays internal');
check(!/(setAndAllowWhileIdle|RUNNING\.set)[\s\S]{0,200}(startVpn|Session\.|TunnelService\.start)/.test(auto),
        'auto update never starts the VPN');

// 2026-11-23: a subscription that never downloaded must not claim a format, and empty nodes explain why.
const profileSrc = src('io/ghx/vpn/core/Profile.java');
const store = src('io/ghx/vpn/core/ProfileStore.java');
const nodes = src('io/ghx/vpn/NodesActivity.java');
const downloader = src('io/ghx/vpn/net/Downloader.java');
check(profileSrc.includes('public static final int TYPE_UNKNOWN = 0;'), 'unknown format has its own type');
check(/TYPE_URI\);/.test(mgr) === false, 'adding a URL profile no longer hard-codes v2ray');
check(mgr.includes('Profile.TYPE_UNKNOWN'), 'a new URL profile starts as unknown');
check(store.includes('p.reconcileFormat();'), 'all loaded profiles derive format from existing content, not stale type');
check(!store.includes('o.optInt("type"'), 'cached type is never trusted as format evidence');
const loadBody = store.slice(store.indexOf('public List<Profile> load()'), store.indexOf('public void save('));
check(!/writeFile\(|save\(|Downloader|Session\.|TunnelService/.test(loadBody), 'loading and relabelling never writes, downloads or starts VPN');
check(profileSrc.includes('type = io.ghx.vpn.sub.SubParser.detect(decodedContent());'), 'profile metadata is reconciled from decoded content');
check(mgr.includes('String text = p.decodedContent();') && mgr.includes('SubParser.parseNodes(text, fmt)'), 'node reader shares decoded source and detected format');
const parser = src('io/ghx/vpn/sub/SubParser.java');
check(subs.includes('SubParser.formatLabel(profile.type,') && subs.includes('typeLabel(Profile profile)'), 'badge uses identified subscription format only');
check(!subs.includes('NodeKind.uniform('), 'protocol composition cannot relabel ordinary URI subscriptions as ql');
check(parser.includes('if (QlParser.hasWrapper(t)) {'), 'ql classification requires an explicit marker');
check(!parser.includes('links.size() > 1') && !parser.includes('QlParser.count('), 'no count or protocol heuristic for ql');
check(subs.includes('"v2ray URI 列表"'), 'import preview names ordinary URI format consistently');
check(parser.includes('case FMT_URI: return "v2ray";'), 'URI-format badge remains v2ray');
check(!subs.includes('"v2ray 链接"'), 'review dialog does not call every link list v2ray');
check(parser.includes('待识别'), 'unknown format is labelled as pending');
check(subs.includes('尚未更新 · 点「刷新」下载'), 'an undownloaded profile says how to fetch it');
check(nodes.includes('pendingDownload') && nodes.includes('这个订阅还没下载成功'),
        'the node list explains an undownloaded subscription');
check(downloader.includes('下载超时（20 秒内没有响应）') && downloader.includes('域名解析失败')
        && downloader.includes('HTTPS 握手失败') && downloader.includes('连接被拒绝'),
        'network failures are explained instead of raw timeouts');
console.log('AuditTunContract: ' + count + ' checks passed; no VPN/native code executed');
