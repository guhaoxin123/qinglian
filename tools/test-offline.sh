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
  "$SRC"/io/ghx/vpn/core/DocumentImport.java "$SRC"/io/ghx/vpn/core/Interval.java "$SRC"/io/ghx/vpn/ui/ColorMath.java \
  "$SRC"/io/ghx/vpn/net/Net.java "$SRC"/io/ghx/vpn/net/SpeedTest.java \
  "$SRC"/io/ghx/vpn/net/UrlSource.java "$SRC"/io/ghx/vpn/core/TrafficStats.java "$SRC"/io/ghx/vpn/core/Profile.java \
  "$P"/tools/testsrc/AuditConfigTest.java "$P"/tools/testsrc/AuditSubTest.java \
  "$P"/tools/testsrc/AuditDocumentImport.java "$P"/tools/testsrc/AuditAppearanceTest.java \
  "$P"/tools/testsrc/AuditSpeedTest.java "$P"/tools/testsrc/AuditIntervalTest.java "$P"/tools/testsrc/AuditTrafficTest.java \
  "$P"/tools/testsrc/AuditFormatRegression.java "$P"/tools/testsrc/AuditProfileFormat.java \
  "$SRC"/io/ghx/vpn/core/UpdatePolicy.java "$SRC"/io/ghx/vpn/core/LogReader.java "$P"/tools/testsrc/AuditUpdateQl.java
for name in AuditConfigTest AuditSubTest AuditDocumentImport AuditAppearanceTest io.ghx.vpn.net.AuditSpeedTest AuditIntervalTest AuditTrafficTest AuditFormatRegression AuditProfileFormat AuditUpdateQl; do
  "$J/java" -cp "$OUT:$TC/android.jar" "$name"
done
# Link-time API classes only: CoreTunStatus.install()/Bridge.INSTANCE are NEVER executed.
"$J/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$TC/android.jar" -d "$OUT" \
  "$SRC"/com/github/kr328/clash/core/bridge/*.java "$SRC"/kotlin/*.java \
  "$SRC"/kotlinx/coroutines/*.java "$SRC"/io/ghx/vpn/core/CoreTunStatus.java \
  "$P"/tools/testsrc/AuditTunStatus.java
"$J/java" -cp "$OUT:$TC/android.jar" io.ghx.vpn.core.AuditTunStatus
# Separate JVM classpath: Android test doubles must never enter the production APK.
SIG="$OUT/signature"
mkdir -p "$SIG"
"$J/javac" -encoding UTF-8 -source 8 -target 8 -nowarn -d "$SIG" \
  "$P"/tools/testsrc/signature-stubs/android/content/*.java \
  "$P"/tools/testsrc/signature-stubs/android/content/pm/*.java \
  "$P"/tools/testsrc/signature-stubs/android/os/*.java \
  "$SRC"/io/ghx/vpn/core/UpdatePolicy.java "$SRC"/io/ghx/vpn/core/SignatureGuard.java \
  "$P"/tools/testsrc/AuditSignature.java
"$J/java" -cp "$SIG" AuditSignature "$P/tools/testsrc/fixtures/original-public-cert.der"
printf '\nOffline JVM tests passed. Source-string checks: run node tools/testsrc/AuditTunContract.js separately.\n'
