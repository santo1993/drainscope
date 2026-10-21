package app.drainscope.battery;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;

/** A snapshot of the phone's current electrical and radio state. */
public class LiveState {

    public int level = -1;
    public double temperatureC = -1;
    public int voltageMv = -1;
    public long currentUa = Long.MIN_VALUE;
    public String currentSource = "";
    public long chargeCounterUah = -1;
    public int health = -1;
    public int status = -1;
    public int plugged = -1;
    public String healthText = "";
    public String statusText = "";
    public String technology = "";
    public boolean screenOn;
    public int brightness = -1;
    public int brightnessMode = -1;
    public float refreshRate = -1;
    public String networkType = "none";
    public int wifiRssi = -1;
    public String wifiSsid = "";
    public boolean gpsEnabled;
    public boolean bluetoothOn;
    public double cpuLoad = -1;
    public long availMemMb, totalMemMb;
    public long uptimeMs;
    public long cycleCount = -1;
    public long chargeFullUah = -1;
    public double watts = -1;
    public int vendorCurrentMa = Integer.MIN_VALUE;
    public String firstUseDate = "";
    public String vendorCycleNote = "";

    public static LiveState sample(Context ctx) {
        LiveState s = new LiveState();
        s.readBattery(ctx);
        s.readDisplay(ctx);
        s.readRadios(ctx);
        s.readMemory(ctx);
        s.readCpu();
        s.uptimeMs = SystemClock.elapsedRealtime();
        return s;
    }

    private void readBattery(Context ctx) {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            chargeCounterUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            long now = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (now != Long.MIN_VALUE && now != 0) {
                currentUa = now;
                currentSource = "BatteryManager";
            }
        }
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
                status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                // Some vendors (Samsung) publish the charge cycle count in the
                // battery broadcast; it is not part of the public API.
                int cc = i.getIntExtra("cycle_count", -1);
                if (cc >= 0) {
                    cycleCount = cc;
                    vendorCycleNote = cc > 0 ? "reported by the firmware"
                            : "the firmware reports 0 (not filled in on this device)";
                }
                int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (temp > -1000) temperatureC = temp / 10.0;
                int volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                if (volt > 0) voltageMv = volt;
                int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (lvl >= 0 && scale > 0) level = Math.round(lvl * 100f / scale);
                healthText = healthText(health);
                statusText = statusText(status);
            }
        } catch (Exception ignored) {
        }
        if (currentUa != Long.MIN_VALUE && voltageMv > 0) {
            watts = (currentUa / 1_000_000.0) * (voltageMv / 1000.0);
        }
    }

    private void readDisplay(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) screenOn = pm.isInteractive();
        } catch (Exception ignored) {
        }
        try {
            brightness = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            brightnessMode = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
        } catch (Exception ignored) {
        }
        try {
            Display d = ctx.getDisplay();
            if (d != null) refreshRate = d.getRefreshRate();
        } catch (Throwable ignored) {
        }
    }

    private void readRadios(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network net = cm.getActiveNetwork();
                NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) networkType = "Wi-Fi";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) networkType = "Cellular";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) networkType = "Ethernet";
                    else networkType = "other";
                }
            }
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                wifiRssi = wm.getConnectionInfo().getRssi();
                wifiSsid = wm.getConnectionInfo().getSSID();
            }
        } catch (Exception ignored) {
        }
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                List<String> providers = lm.getProviders(true);
                gpsEnabled = providers != null && providers.contains(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception ignored) {
        }
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            bluetoothOn = adapter != null && adapter.isEnabled();
        } catch (Throwable ignored) {
        }
    }

    private void readMemory(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                availMemMb = mi.availMem / (1024 * 1024);
                totalMemMb = mi.totalMem / (1024 * 1024);
            }
        } catch (Exception ignored) {
        }
    }

    /** Reads /proc/stat twice to estimate CPU busy percentage. */
    private void readCpu() {
        long[] a = cpuTimes();
        if (a == null) return;
        try {
            Thread.sleep(120);
        } catch (InterruptedException ignored) {
        }
        long[] b = cpuTimes();
        if (b == null) return;
        long totalDelta = 0, idleDelta = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            long d = b[i] - a[i];
            if (d < 0) d = 0;
            totalDelta += d;
            if (i == 3 || i == 4) idleDelta += d; // idle + iowait
        }
        if (totalDelta > 0) {
            cpuLoad = 100.0 * (totalDelta - idleDelta) / totalDelta;
        }
    }

    private long[] cpuTimes() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/stat"));
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] parts = line.trim().split("\\s+");
            long[] out = new long[parts.length - 1];
            for (int i = 1; i < parts.length; i++) {
                out[i - 1] = Long.parseLong(parts[i]);
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (br != null) br.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Fills the values that need shell privileges (vendor current, counter, cycles). */
    public void applyShellFacts(String shellOutput) {
        if (shellOutput == null || shellOutput.isEmpty()) return;
        for (String line : shellOutput.split("\n")) {
            String t = line.trim();
            if (t.startsWith("current now:")) {
                try {
                    // Samsung and most OEM firmwares report this in milliampere
                    vendorCurrentMa = Integer.parseInt(t.substring("current now:".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (t.startsWith("charge counter:")) {
                try {
                    chargeCounterUah = Long.parseLong(t.substring("charge counter:".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (t.startsWith("Charge counter:")) {
                try {
                    chargeCounterUah = Long.parseLong(t.substring("Charge counter:".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (t.startsWith("CURRENT_UA=")) {
                try {
                    currentUa = Long.parseLong(t.substring("CURRENT_UA=".length()).trim());
                    currentSource = "sysfs";
                } catch (NumberFormatException ignored) {
                }
            } else if (t.startsWith("CYCLE_COUNT=")) {
                try {
                    cycleCount = Long.parseLong(t.substring("CYCLE_COUNT=".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (t.startsWith("CHARGE_FULL_UAH=")) {
                try {
                    chargeFullUah = Long.parseLong(t.substring("CHARGE_FULL_UAH=".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (t.startsWith("battery FirstUseDate:")) {
                firstUseDate = t.substring("battery FirstUseDate:".length()).trim().replace("[", "").replace("]", "");
            }
        }
        if (vendorCurrentMa != Integer.MIN_VALUE) {
            // the firmware value is the more trustworthy one on most devices
            currentUa = (long) vendorCurrentMa * 1000L;
            currentSource = "firmware";
        }
        if (currentUa != Long.MIN_VALUE && voltageMv > 0) {
            watts = (currentUa / 1_000_000.0) * (voltageMv / 1000.0);
        }
    }

    public static String healthText(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheating";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Unspecified failure";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            case -1: return "?";
            default: return "Other";
        }
    }

    public static String statusText(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            case -1: return "?";
            default: return "Unknown";
        }
    }
}
