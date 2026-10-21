#!/bin/bash
# Builds drainscope.apk on an ARM64 Android host.
#
# Google's build-tools ship x86_64-only binaries (aapt2, zipalign) which cannot
# run here, so the APK is assembled from:
#   javac      -> class files        (JDK 17, --release 8, android.jar on cp)
#   d8.jar     -> classes.dex        (pure Java, from build-tools)
#   axml_write -> AndroidManifest.xml (our own AXML compiler)
#   apksigner  -> v1/v2/v3 signature  (pure Java, from build-tools)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/data/user/0/com.androidharness.app/files/.harness-scratch/android-sdk}"
JAVA_HOME="${JAVA_HOME:-/data/user/0/com.androidharness.app/files/linux/lib/jvm/java-17-openjdk}"
WORK="${BUILD_WORK:-$ROOT/build}"
LIBS="$WORK/libs"
OUT="$WORK/out"
export PATH="$JAVA_HOME/bin:$PATH"

ANDROID_JAR="$SDK/platforms/android-36/android.jar"
D8_JAR="$SDK/build-tools/36.0.0/lib/d8.jar"
APKSIGNER_JAR="$SDK/build-tools/36.0.0/lib/apksigner.jar"

for f in "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR"; do
  [ -f "$f" ] || { echo "MISSING $f"; exit 1; }
done

mkdir -p "$LIBS" "$OUT" "$WORK/classes" "$WORK/dex"

# ---------------------------------------------------------------- dependencies
SHIZUKU_VERSION=13.1.5
M2=https://repo1.maven.org/maven2
fetch() { # url dest
  if [ ! -f "$2" ]; then
    echo "  download $(basename "$2")"
    curl -sSL -m 180 -o "$2" "$1"
  fi
}

echo "== dependencies =="
fetch "$M2/dev/rikka/shizuku/api/$SHIZUKU_VERSION/api-$SHIZUKU_VERSION.aar" "$LIBS/shizuku-api.aar"
fetch "$M2/dev/rikka/shizuku/provider/$SHIZUKU_VERSION/provider-$SHIZUKU_VERSION.aar" "$LIBS/shizuku-provider.aar"
fetch "$M2/dev/rikka/shizuku/aidl/$SHIZUKU_VERSION/aidl-$SHIZUKU_VERSION.aar" "$LIBS/shizuku-aidl.aar"
fetch "$M2/dev/rikka/shizuku/shared/$SHIZUKU_VERSION/shared-$SHIZUKU_VERSION.aar" "$LIBS/shizuku-shared.aar"
fetch "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar" "$LIBS/annotation.jar"

rm -rf "$WORK/deps" && mkdir -p "$WORK/deps"
for aar in "$LIBS"/shizuku-*.aar; do
  name=$(basename "$aar" .aar)
  rm -rf "$WORK/deps/$name" && mkdir -p "$WORK/deps/$name"
  unzip -q -o "$aar" -d "$WORK/deps/$name"
  if [ -f "$WORK/deps/$name/classes.jar" ]; then
    (cd "$WORK/deps/$name" && unzip -q -o classes.jar -d classes)
  fi
done
rm -rf "$WORK/deps/annotation" && mkdir -p "$WORK/deps/annotation"
unzip -q -o "$LIBS/annotation.jar" -d "$WORK/deps/annotation"

rm -rf "$WORK/depclasses" && mkdir -p "$WORK/depclasses"
for d in "$WORK"/deps/*/classes; do
  [ -d "$d" ] && cp -r "$d"/. "$WORK/depclasses"/
done
if [ -d "$WORK/deps/annotation/androidx" ]; then
  cp -r "$WORK/deps/annotation/androidx" "$WORK/depclasses"/
fi
(cd "$WORK/depclasses" && jar cf "$LIBS/deps.jar" .)

# ---------------------------------------------------------------- compile
echo "== javac =="
rm -rf "$WORK/classes" && mkdir -p "$WORK/classes"
find "$ROOT/app/src/main/java" -name '*.java' > "$WORK/sources.txt"
javac -nowarn -encoding UTF-8 --release 8 \
  -classpath "$ANDROID_JAR:$LIBS/deps.jar" \
  -d "$WORK/classes" @"$WORK/sources.txt"
jar cf "$WORK/app-classes.jar" -C "$WORK/classes" .

# ---------------------------------------------------------------- dex
echo "== d8 =="
rm -rf "$WORK/dex" && mkdir -p "$WORK/dex"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api 24 --lib "$ANDROID_JAR" \
  --output "$WORK/dex" \
  "$WORK/app-classes.jar" "$LIBS/deps.jar"

# ---------------------------------------------------------------- manifest
echo "== resources =="
python3 "$ROOT/tools/make_resources.py" "$OUT" || exit 1

echo "== manifest =="
python3 "$ROOT/tools/axml_write.py" "${MANIFEST_XML:-$ROOT/app/src/main/AndroidManifest.xml}" "$OUT/AndroidManifest.xml"

# ---------------------------------------------------------------- package
echo "== package =="
python3 - "$OUT" <<'PY'
import struct, sys, zipfile, os
out = sys.argv[1]
apk = os.path.join(out, "drainscope-unsigned.apk")


def write_aligned(zf, name, data, align=4):
    """Stores an entry uncompressed and 4-byte aligned, like aapt2 does.

    resources.arsc is mapped straight out of the APK, so it must be stored
    (not deflated) and its data has to start on a 4 byte boundary.
    """
    zi = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    name_bytes = name.encode("utf-8")
    header_len = 30 + len(name_bytes)
    pos = zf.fp.tell()
    need = (align - ((pos + header_len) % align)) % align
    extra_len = ((need + 3) // 4) * 4
    if extra_len:
        zi.extra = struct.pack("<HH", 0x7570, extra_len - 4) + b"\x00" * (extra_len - 4)
    zf.writestr(zi, data)


with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "AndroidManifest.xml"), "AndroidManifest.xml")
    z.write(os.path.join(out, "..", "dex", "classes.dex"), "classes.dex")
    arsc = os.path.join(out, "resources.arsc")
    if os.path.exists(arsc):
        with open(arsc, "rb") as f:
            write_aligned(z, "resources.arsc", f.read())
    icon = os.path.join(out, "res", "drawable", "ic_launcher.png")
    if os.path.exists(icon):
        with open(icon, "rb") as f:
            write_aligned(z, "res/drawable/ic_launcher.png", f.read())
print("  wrote", apk)
PY

# ---------------------------------------------------------------- sign
echo "== sign =="
KS="$WORK/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DrainScope Debug,O=DrainScope,C=DE" >/dev/null 2>&1
fi
java -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/drainscope.apk" "$OUT/drainscope-unsigned.apk"

java -cp "$APKSIGNER_JAR" com.android.apksigner.ApkSignerTool verify --print-certs "$OUT/drainscope.apk" | head -5
ls -la "$OUT/drainscope.apk"
echo "BUILD_OK"
