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
console.log('AuditTunContract: ' + count + ' checks passed; no VPN/native code executed');
