#!/system/bin/sh
# 设备端构建脚本（在 /data/local/tmp 下以 shell 用户运行）
set -e
TC=/data/local/tmp/tc
J=$TC/jvm/java-17-openjdk/bin
P=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
SRC=$P/app/src/main
# 独立输出目录：保留旧包，构建失败不能冒充上一版。
OUT=$P/build-$(date +%Y%m%d-%H%M%S)-$$
export LD_LIBRARY_PATH=$TC/jvm/java-17-openjdk/lib:$TC/jvm/java-17-openjdk/lib/server:$TC/lib

mkdir -p $OUT/classes $OUT/dex $OUT/apk $OUT/gen

echo "[1/6] aapt2 compile"
$TC/aapt2 compile --dir $SRC/res -o $OUT/res.zip >/dev/null

echo "[2/6] aapt2 link"
$TC/aapt2 link -o $OUT/base.apk \
  -I $TC/android.jar \
  --manifest $SRC/AndroidManifest.xml \
  --java $OUT/gen \
  -R $OUT/res.zip \
  --min-sdk-version 24 --target-sdk-version 35 \
  --auto-add-overlay

echo "[3/6] javac"
find $SRC/java $OUT/gen -name '*.java' > $OUT/sources.txt
$J/javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath $TC/android.jar -d $OUT/classes @$OUT/sources.txt
for c in io/ghx/vpn/MainActivity io/ghx/vpn/NodesActivity io/ghx/vpn/SubsActivity io/ghx/vpn/svc/TunnelService; do
  test -f "$OUT/classes/$c.class" || { echo "缺少必要 class: $c"; exit 1; }
done

echo "[4/6] d8"
find $OUT/classes -name '*.class' > $OUT/classes.txt
$J/java -cp $TC/d8.jar com.android.tools.r8.D8 --min-api 24 --lib $TC/android.jar --output $OUT/dex @$OUT/classes.txt

echo "[5/6] 组装"
$J/javac -nowarn -d $P/tools $P/tools/ZipPack.java
cd $OUT/apk && unzip -q -o $OUT/base.apk
cp $OUT/dex/classes.dex $OUT/apk/classes.dex
if [ -d /sdcard/llb/libs/arm64-v8a ]; then
  mkdir -p $OUT/apk/lib/arm64-v8a
  cp /sdcard/llb/libs/arm64-v8a/*.so $OUT/apk/lib/arm64-v8a/
fi
$J/java -cp $P/tools ZipPack $OUT/apk $OUT/unsigned.apk

echo "[6/6] 签名"
if [ ! -f $TC/litlink.jks ]; then
  $J/keytool -genkeypair -storetype JKS -keystore $TC/litlink.jks -alias litlink -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass litlink123 -keypass litlink123 \
    -dname "CN=LitLink, O=LitLink, C=CN" >/dev/null 2>&1
fi
$J/java -jar $TC/apksigner.jar sign --ks $TC/litlink.jks --ks-type JKS --ks-pass pass:litlink123 \
  --key-pass pass:litlink123 --out $OUT/LitLink.apk $OUT/unsigned.apk
$J/java -jar $TC/apksigner.jar verify --verbose $OUT/LitLink.apk
printf '%s\n' "$OUT/LitLink.apk" > "$P/latest-apk.txt"
ls -l $OUT/LitLink.apk
