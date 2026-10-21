#!/bin/bash
# Regenerates tools/framework_ids.txt from the Android SDK.
#
# The manifest compiler needs the framework attribute ids (android:exported
# and friends) to build the binary resource map. They are extracted from the
# SDK's android.jar with javap.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/data/user/0/com.androidharness.app/files/.harness-scratch/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar not found: $ANDROID_JAR" >&2
  echo "install it with: sdkmanager --sdk_root=$SDK 'platforms;android-36'" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/data/user/0/com.androidharness.app/files/linux/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"

javap -constants -cp "$ANDROID_JAR" android.R\$attr android.R\$style android.R\$drawable \
  > "$HERE/framework_ids.txt"

echo "wrote $HERE/framework_ids.txt ($(grep -c 'public static final int' "$HERE/framework_ids.txt") ids)"
