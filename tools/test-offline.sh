#!/system/bin/sh
# Run only synthetic offline tests; never invokes SelfTest, JNI, networking, or VPN.
set -eu
TC=/data/local/tmp/tc
J=$TC/jvm/java-17-openjdk/bin
P=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
SRC=$P/app/src/main/java
OUT=$P/test-$(date +%Y%m%d-%H%M%S)-$$
export LD_LIBRARY_PATH=$TC/jvm/java-17-openjdk/lib:$TC/jvm/java-17-openjdk/lib/server:$TC/lib
mkdir -p "$OUT"
"$J/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$TC/android.jar" -d "$OUT" \
  "$SRC"/io/ghx/vpn/sub/*.java \
  "$SRC"/io/ghx/vpn/core/ConfigBuilder.java "$SRC"/io/ghx/vpn/core/RuleFilter.java \
  "$SRC"/io/ghx/vpn/core/DocumentImport.java "$SRC"/io/ghx/vpn/ui/ColorMath.java \
  "$SRC"/io/ghx/vpn/net/Net.java "$SRC"/io/ghx/vpn/net/SpeedTest.java \
  "$P"/tools/testsrc/AuditConfigTest.java "$P"/tools/testsrc/AuditSubTest.java \
  "$P"/tools/testsrc/AuditDocumentImport.java "$P"/tools/testsrc/AuditAppearanceTest.java \
  "$P"/tools/testsrc/AuditSpeedTest.java
for name in AuditConfigTest AuditSubTest AuditDocumentImport AuditAppearanceTest io.ghx.vpn.net.AuditSpeedTest; do
  "$J/java" -cp "$OUT:$TC/android.jar" "$name"
done
# Link-time API classes only: CoreTunStatus.install()/Bridge.INSTANCE are NEVER executed.
"$J/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$TC/android.jar" -d "$OUT" \
  "$SRC"/com/github/kr328/clash/core/bridge/*.java "$SRC"/kotlin/*.java \
  "$SRC"/kotlinx/coroutines/*.java "$SRC"/io/ghx/vpn/core/CoreTunStatus.java \
  "$P"/tools/testsrc/AuditTunStatus.java
"$J/java" -cp "$OUT:$TC/android.jar" io.ghx.vpn.core.AuditTunStatus
printf '\nOffline JVM tests passed. Source-string checks: run node tools/testsrc/AuditTunContract.js separately.\n'
