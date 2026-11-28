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
# Refuse a seemingly successful APK that cannot load its bundled native core.
mkdir -p "$OUT/apk/lib/arm64-v8a"
for lib in libbridge.so libclash.so libc++_shared.so liblkengine.so; do
  test -s "/sdcard/llb/libs/arm64-v8a/$lib" || { echo "缺少原生库: $lib" >&2; exit 1; }
  cp "/sdcard/llb/libs/arm64-v8a/$lib" "$OUT/apk/lib/arm64-v8a/$lib"
done
$J/java -cp $P/tools ZipPack $OUT/apk $OUT/unsigned.apk

echo "[6/6] 签名"
# Never silently generate a replacement key: startup is pinned to the original certificate.
if [ ! -f $TC/litlink.jks ]; then
  echo "缺少原签名密钥，停止构建（不会生成新密钥）" >&2
  exit 1
fi
$J/java -jar $TC/apksigner.jar sign --ks $TC/litlink.jks --ks-type JKS --ks-pass pass:litlink123 \
  --key-pass pass:litlink123 --out $OUT/LitLink.apk $OUT/unsigned.apk
$J/java -jar $TC/apksigner.jar verify --verbose $OUT/LitLink.apk
CERTS=$($J/java -jar $TC/apksigner.jar verify --print-certs $OUT/LitLink.apk)
printf '%s\n' "$CERTS"
case "$CERTS" in
  *"certificate SHA-256 digest: 0a9a6adfe1f9d72ea7acb819dbfb84723c427b10ab3128f7fff8517388eec951"*) ;;
  *) echo "签名与应用启动校验基准不一致，拒绝发行" >&2; exit 1 ;;
esac
printf '%s\n' "$OUT/LitLink.apk" > "$P/latest-apk.txt"
ls -l $OUT/LitLink.apk
