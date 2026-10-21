#!/usr/bin/env python3
"""Package an arbitrary binary manifest with our classes.dex and sign it."""
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SDK = os.environ.get("ANDROID_SDK_ROOT", "/data/user/0/com.androidharness.app/files/.harness-scratch/android-sdk")
JAVA_HOME = os.environ.get("JAVA_HOME", "/data/user/0/com.androidharness.app/files/linux/lib/jvm/java-17-openjdk")
APKSIGNER_JAR = os.path.join(SDK, "build-tools", "36.0.0", "lib", "apksigner.jar")


def main():
    manifest_axml, out_apk = sys.argv[1], sys.argv[2]
    dex = os.path.join(ROOT, "build", "dex", "classes.dex")
    unsigned = out_apk.replace(".apk", "-unsigned.apk")
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(manifest_axml, "AndroidManifest.xml")
        z.write(dex, "classes.dex")
    env = dict(os.environ)
    env["PATH"] = os.path.join(JAVA_HOME, "bin") + ":" + env.get("PATH", "")
    subprocess.check_call(["java", "-cp", APKSIGNER_JAR, "com.android.apksigner.ApkSignerTool", "sign",
                           "--ks", os.path.join(ROOT, "build", "debug.keystore"),
                           "--ks-pass", "pass:android", "--key-pass", "pass:android",
                           "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                           "--v3-signing-enabled", "true", "--out", out_apk, unsigned], env=env)
    print(out_apk)


if __name__ == "__main__":
    main()
