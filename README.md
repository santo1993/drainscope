# DrainScope

[![Build](https://github.com/santo1993/drainscope/actions/workflows/build.yml/badge.svg)](https://github.com/santo1993/drainscope/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/santo1993/drainscope.svg)](https://github.com/santo1993/drainscope/releases/latest)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

An Android app that answers the question *"which app — and which piece of
hardware — is draining my battery?"*

It shows, for the period since the last charge:

* **per app**: CPU time, screen-on/foreground time, Wi-Fi and mobile radio
  use, audio, sensors, wakelock time, wakeup count and an estimated share of
  the measured discharge,
* **per hardware part**: display (on-time, brightness buckets, refresh-rate
  buckets), Wi-Fi radio (active/scan/sleep), cellular radio (active, Rx, signal
  strength distribution), GPS (CN0 quality), Bluetooth (scan/Rx/idle, mAh),
  wakelocks and wakeup sources,
* **live values**: level, voltage, current, power, temperature, charge
  counter, first-use date, CPU load, memory, refresh rate, network type,
* **battery health & charge cycles**: the firmware's cycle count where the
  vendor publishes it, rated capacity, the peak full-charge capacity observed,
  total energy used, cycles counted by DrainScope, average mAh per cycle and
  cycles per month,
* **history**: hourly snapshots in a local database, discharge-rate chart and
  CSV export.

Two data sources:

| Source | What it gives | Requirement |
|---|---|---|
| **Shizuku** (preferred) | measured values from `dumpsys batterystats` | Shizuku running, one tap to allow |
| **Usage stats** (fallback) | foreground time per app, converted to an mAh *estimate* | "Usage access" for the app |

Without Shizuku the app still works, but the per-app numbers are estimates.

## Install

Download the APK from the [releases page](https://github.com/santo1993/drainscope/releases),
or build it yourself:

```bash
adb install -r drainscope.apk
```

Then open **DrainScope** and tap **Shizuku → Allow all the time**, and
**Usage access → DrainScope → Allow**.

## Build it yourself

**With Gradle / Android Studio** (the normal path)

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # needs a signing key, see the workflow
```

**On the phone (no Android Studio, no x86_64 needed)**

```bash
cd drainscope
bash tools/build_apk.sh
```

The script needs `JAVA_HOME` (JDK 17), an Android SDK with
`platforms;android-36` and `build-tools;36.0.0`, and `python3`.
APK: `build/out/drainscope.apk`.

`tools/build_apk.sh` exists because Google's `aapt2` ships as an
**x86_64-only** binary and cannot run on an ARM64 phone; the script compiles
the manifest and the resource table itself (see below). `tools/cycle.sh`
does build → install → launch → crash check in one go.

### Why a hand-written manifest compiler

`tools/axml_write.py` compiles `app/src/main/AndroidManifest.xml` into the
binary AXML format that Android's package parser expects. It reproduces
aapt2's exact layout, which the framework is picky about:

* UTF-16 string pool (not UTF-8),
* booleans encoded as `0xFFFFFFFF` / `0x00000000`,
* a resource map whose entries are the framework attribute ids
  (`android:exported` = `0x01010010`, …) in **ascending** order,
* attribute names sorted by resource id inside the string pool, and
* **each element's attributes sorted by their string-pool index**.

Getting any of these wrong is not a warning: the framework silently drops the
attribute (e.g. `android:exported` becomes `false` and the app crashes on
startup) or refuses to parse the APK at all ("Corrupt XML binary file" — the
string pool size must also be a multiple of 4).

### Why a hand-written resource table

The launcher icon needs a resource, so `tools/arsc.py` writes a minimal
`resources.arsc` (one package, one `drawable` type) and `tools/make_icon.py`
renders the icon PNG in pure Python — this phone has no imaging libraries.
The rules the framework enforces:

* a type spec chunk (`0x0202`) before every type chunk (`0x0201`),
* `ResTable_type.headerSize` = 84 (the size the device expects),
* the entry offset array directly after the header, the entry data at
  `entriesStart`,
* every chunk size a multiple of 4,
* `resources.arsc` stored **uncompressed** and 4-byte aligned in the APK,
  because the loader maps it straight out of the file.

`tools/make_resources.py` ties both together; the app icon is referenced from
the manifest as the raw id `@0x7f010000`.

## Use it on another phone

The APK is device independent (minSdk 24, arm64/v7a/x86_64, no resources, no
Google Play services). Copy `drainscope.apk` over and install it. For the
privileged data, that phone needs Shizuku running (or root); without it the
app falls back to usage-stats estimates.

If you only want a one-off diagnosis on a second phone and can use a computer,
this is enough — no app needed:

```bash
adb shell dumpsys batterystats --charged > batterystats.txt
```

Upload that file to <https://batterystats.degroot.nl/> (Google's *Battery
Historian*) for a full breakdown, or read the interesting parts directly:

```bash
grep -E "^  UID" batterystats.txt        # per-app CPU / screen / radio
grep -A30 "All partial wake locks" batterystats.txt
grep -E "Discharge:|Screen on discharge|Time on battery" batterystats.txt
```

## Layout

```
drainscope/
  app/src/main/AndroidManifest.xml     single source of truth for the manifest
  app/src/main/res/drawable/           the launcher icon (a normal resource)
  app/src/main/java/app/drainscope/battery/
      MainActivity.java                UI (4 tabs, built in code)
      LiveState.java                   live battery / display / radio values
      ShizukuShell.java                Shizuku client (chunked shell exec)
      ShellService.java                runs in the Shizuku helper process (uid 2000)
      BatteryStatsParser.java          parses `dumpsys batterystats`
      UsageSampler.java                rootless fallback (usage stats)
      HistoryStore.java                SQLite history + snapshots
      SampleJob.java, BootReceiver.java  hourly background sampling
      AppResolver.java, Ui.java        uid -> app names/icons, view helpers
  tools/
      build_apk.sh                     javac + d8 + axml + arsc + apksigner
      axml_write.py                    XML -> binary AXML compiler
      axml_read.py                     binary AXML reader (verification)
      arsc.py                          resources.arsc writer/reader
      make_icon.py                     renders the launcher icon PNG
      make_resources.py                icon + resource table
      crop_png.py                      crop a screenshot (pure python)
      cycle.sh                         build + install + launch + report
```

## Contributing

Pull requests are welcome. The app has **no resources** (no layouts, no
strings.xml) and depends only on the Shizuku API, so a change usually touches
one Java file. Run the build before opening a PR:

```bash
./gradlew :app:assembleDebug          # the normal path
bash tools/build_apk.sh                # the on-device path
```

`tools/cycle.sh` builds, installs and launches on a connected phone and
reports whether the app came up without a crash.

## License

Apache License 2.0 — see [LICENSE](LICENSE). The [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
that the app talks to is MIT licensed and is fetched as a dependency, not
bundled.

## Notes

* The per-app mAh number is a *model*: the app's share of the measured
  discharge (CPU + foreground + radio use), scaled to the total discharge the
  kernel reported. It is a ranking aid, not a meter reading.
* The rootless estimate assumes ~8 mAh per minute of foreground time; it is
  labelled as an estimate everywhere.
* The cycle counter is maintained by DrainScope itself (one cycle = one full
  charge worth of energy used) and only covers the time the app was installed.
  A vendor cycle count is shown when the firmware publishes one; Samsung
  exposes `cycle_count` in the battery broadcast but reports 0 on this device,
  and `/sys/class/power_supply/battery/cycle_count` is not readable even for
  the shell user.
* Nothing leaves the phone. The database is local; exports are written to the
  app's own external files folder (`Android/data/app.drainscope.battery/files/exports`).
