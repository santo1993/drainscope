#!/bin/bash
# Build + install + launch + report, in one go. Run with cwd=/ so the shell
# uses the Shizuku (shell uid) tier, which can call pm.
set -uo pipefail

ROOT=/storage/emulated/0/Android/data/com.androidharness.app/files/workspace/drainscope
WS=/storage/emulated/0/Android/data/com.androidharness.app/files/workspace
export ANDROID_SDK_ROOT="$WS/.harness/sdk"
export BUILD_WORK=/data/local/tmp/ds-build
export JAVA_HOME=/data/local/tmp/androidharness/com.androidharness.app/linux/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
PKG=app.drainscope.battery
MANIFEST_XML="$1"
case "$MANIFEST_XML" in
  /*) ;;
  *) MANIFEST_XML="$WS/$MANIFEST_XML" ;;
esac

cd "$ROOT"
rm -rf "$BUILD_WORK" 2>/dev/null
MANIFEST_XML="$MANIFEST_XML" bash tools/build_apk.sh > "$WS/.harness/out/build.log" 2>&1
if ! grep -q BUILD_OK "$WS/.harness/out/build.log"; then
  echo "BUILD FAILED"; tail -20 "$WS/.harness/out/build.log"; exit 1
fi
cp "$BUILD_WORK/out/drainscope.apk" /data/local/tmp/drainscope.apk || { echo "copy failed"; exit 1; }
pm uninstall $PKG >/dev/null 2>&1
pm install -r /data/local/tmp/drainscope.apk 2>&1 | head -4
appops set $PKG android:get_usage_stats allow >/dev/null 2>&1
logcat -b crash -c >/dev/null 2>&1
am force-stop $PKG
am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 5
CRASH=$(logcat -b crash -d -t 60 2>/dev/null | grep -E "Caused by|FATAL" | head -3)
if [ -n "$CRASH" ]; then
  echo "RESULT: CRASH"
  echo "$CRASH"
else
  echo "RESULT: OK (no crash)"
  dumpsys window 2>/dev/null | grep mCurrentFocus | head -1
fi
