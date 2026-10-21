package app.drainscope.battery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * Main screen: live battery state, per-app drain, hardware breakdown and history.
 */
public class MainActivity extends Activity implements ShizukuShell.Listener {

    private static final int TAB_LIVE = 0, TAB_APPS = 1, TAB_HARDWARE = 2, TAB_HISTORY = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private TextView statusLine;
    private TextView titleLine;
    private Button[] tabButtons;
    private int tab = TAB_LIVE;

    private ShizukuShell shell;
    private AppResolver resolver;
    private HistoryStore history;
    private boolean busy;

    private LiveState live;
    private BatteryStatsParser parser;
    private String rawDump = "";
    private List<UsageSampler.AppUsage> usageFallback = new ArrayList<UsageSampler.AppUsage>();
    private String lastError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // the app draws its own header, so no action bar
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
        resolver = new AppResolver(this);
        history = new HistoryStore(this);
        shell = new ShizukuShell(this, this);

        setContentView(buildRoot());
        SampleJob.schedule(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shell.bind();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        history.close();
    }

    // ------------------------------------------------------------------ UI

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.isNight(this) ? 0xFF121417 : 0xFFF7F8FA);
        root.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        // targetSdk 35+ draws edge to edge: keep the content out of the
        // status and navigation bars
        root.setFitsSystemWindows(true);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.IconView icon = new Ui.IconView(this);
        headerRow.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        titleLine = Ui.text(this, "DrainScope", 20, Ui.textPrimary(this));
        titleLine.setTypeface(titleLine.getTypeface(), android.graphics.Typeface.BOLD);
        headerRow.addView(titleLine, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(headerRow);

        statusLine = Ui.text(this, "starting…", 12, Ui.textSecondary(this));
        statusLine.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        root.addView(statusLine);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));
        actions.addView(button("Shizuku", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (shell.isGranted()) {
                    shell.bind();
                    refresh();
                } else {
                    shell.requestPermission();
                    toast("Shizuku permission requested");
                }
            }
        }));
        actions.addView(button("Usage access", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUsageAccessSettings();
            }
        }));
        actions.addView(button("Export", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportAll();
            }
        }));
        root.addView(actions);

        // tabs
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        String[] names = {"Live", "Apps", "Hardware", "History"};
        tabButtons = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(13);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tab = idx;
                    render();
                }
            });
            tabButtons[i] = b;
            tabRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabRow);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(l);
        b.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private void render() {
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setAlpha(i == tab ? 1f : 0.55f);
        }
        content.removeAllViews();
        switch (tab) {
            case TAB_APPS: renderApps(); break;
            case TAB_HARDWARE: renderHardware(); break;
            case TAB_HISTORY: renderHistory(); break;
            default: renderLive(); break;
        }
    }

    private void updateStatus() {
        String s;
        if (!shell.isServerRunning()) {
            s = "Shizuku: not running — start Shizuku for full data";
        } else if (!shell.isGranted()) {
            s = "Shizuku: running, permission not granted — tap Shizuku to allow";
        } else if (!shell.isBound()) {
            s = "Shizuku: granted, helper not connected";
        } else {
            s = "Shizuku: connected (shell privileges)";
        }
        s += "   ·   usage access: " + (UsageSampler.hasPermission(this) ? "yes" : "no");
        if (live != null && live.level >= 0) {
            s += "   ·   battery " + live.level + "%";
        }
        statusLine.setText(s);
    }

    // --------------------------------------------------------------- data

    private void refresh() {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LiveState l = LiveState.sample(MainActivity.this);
                String dump = "";
                String facts = "";
                if (shell.isGranted()) {
                    if (!shell.isBound()) shell.bind();
                    dump = shell.exec("dumpsys batterystats --charged");
                    facts = shell.exec("dumpsys battery");
                }
                l.applyShellFacts(facts);
                final List<UsageSampler.AppUsage> fallback =
                        UsageSampler.hasPermission(MainActivity.this)
                                ? UsageSampler.sample(MainActivity.this, System.currentTimeMillis() - 24 * 3600 * 1000L)
                                : new ArrayList<UsageSampler.AppUsage>();
                final String dumpFinal = dump == null ? "" : dump;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        live = l;
                        rawDump = dumpFinal;
                        parser = dumpFinal.isEmpty() ? null : new BatteryStatsParser(dumpFinal);
                        usageFallback = fallback;
                        busy = false;
                        updateStatus();
                        render();
                        storeSnapshot();
                        if (parser == null && shell.isGranted() && !shell.isBound()) {
                            // the helper process was still starting: retry shortly
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    refresh();
                                }
                            }, 1500);
                        }
                    }
                });
            }
        }, "drainscope-refresh").start();
    }

    private void storeSnapshot() {
        try {
            // do not flood the history with a snapshot per refresh, but let a
            // measured snapshot replace an estimated one
            List<HistoryStore.Snapshot> recent = history.listSince(System.currentTimeMillis() - 5 * 60 * 1000L);
            if (!recent.isEmpty()) {
                String newSource = parser != null ? "shizuku" : "rootless";
                for (HistoryStore.Snapshot r : recent) {
                    if (r.source != null && r.source.equals(newSource)) return;
                }
            }
            HistoryStore.Snapshot s = new HistoryStore.Snapshot();
            s.ts = System.currentTimeMillis();
            s.level = live == null ? -1 : live.level;
            s.chargeUah = live == null ? -1 : live.chargeCounterUah;
            s.source = parser != null ? "shizuku" : "rootless";
            if (parser != null) {
                BatteryStatsParser.Summary sum = parser.summary();
                if (sum.timeOnBatteryMs > 0) {
                    s.mahPerHour = sum.dischargeMah / (sum.timeOnBatteryMs / 3600000.0);
                }
                s.screenOnRatio = sum.timeOnBatteryMs > 0 ? (double) sum.screenOnMs / sum.timeOnBatteryMs : -1;
                List<BatteryStatsParser.UidStat> top = sortedUids();
                if (!top.isEmpty()) {
                    s.topApp = top.get(0).label;
                    s.topPct = top.get(0).cpu;
                }
                history.insertOffenders(s.ts, top);
            }
            history.insert(s);
        } catch (Throwable ignored) {
        }
    }

    private List<BatteryStatsParser.UidStat> sortedUids() {
        List<BatteryStatsParser.UidStat> list = new ArrayList<BatteryStatsParser.UidStat>();
        if (parser == null) return list;
        for (BatteryStatsParser.UidStat u : parser.uids()) {
            u.label = resolver.labelForUid(u.uid);
            u.pkg = resolver.packageForUid(u.uid);
            if (u.cpu > 0.05 || u.screen > 0.05 || u.wakelockMs > 0) list.add(u);
        }
        Collections.sort(list, new Comparator<BatteryStatsParser.UidStat>() {
            @Override
            public int compare(BatteryStatsParser.UidStat a, BatteryStatsParser.UidStat b) {
                return Double.compare(score(b), score(a));
            }
        });
        return list;
    }

    private static double score(BatteryStatsParser.UidStat u) {
        return u.cpu + 0.6 * u.screen + 0.4 * u.wifi + 0.25 * u.mobile + 0.15 * u.audio;
    }

    // --------------------------------------------------------------- tabs

    private void renderLive() {
        if (live == null) {
            content.addView(Ui.text(this, "collecting…", 14, Ui.textSecondary(this)));
            return;
        }
        LinearLayout big = Ui.card(this, null);
        TextView pct = Ui.text(this, live.level + "%", 40, live.level <= 20 ? Ui.BAD : Ui.textPrimary(this));
        pct.setTypeface(pct.getTypeface(), android.graphics.Typeface.BOLD);
        big.addView(pct);
        Ui.addRow(big, "Status", live.statusText + (live.plugged > 0 ? " (plugged)" : ""), Ui.textPrimary(this));
        Ui.addRow(big, "Health", live.healthText, live.healthText.equals("Good") ? Ui.GOOD : Ui.WARN);
        if (live.voltageMv > 0) Ui.addRow(big, "Voltage", live.voltageMv + " mV", Ui.textPrimary(this));
        if (live.currentUa != Long.MIN_VALUE) {
            Ui.addRow(big, "Current", String.format("%.0f mA (%s)", live.currentUa / 1000.0, live.currentSource),
                    live.currentUa < 0 ? Ui.WARN : Ui.GOOD);
        }
        if (live.watts != 0) Ui.addRow(big, "Power", String.format("%.2f W", Math.abs(live.watts)), Ui.textPrimary(this));
        if (live.temperatureC > 0) {
            Ui.addRow(big, "Temperature", String.format("%.1f °C", live.temperatureC),
                    live.temperatureC > 40 ? Ui.BAD : Ui.textPrimary(this));
        }
        if (live.vendorCurrentMa != Integer.MIN_VALUE) {
            Ui.addRow(big, "Firmware current", live.vendorCurrentMa + " mA", Ui.textSecondary(this));
        }
        if (live.firstUseDate != null && live.firstUseDate.length() > 0) {
            Ui.addRow(big, "First use", live.firstUseDate, Ui.textPrimary(this));
        }
        if (live.chargeCounterUah > 0) {
            Ui.addRow(big, "Charge counter", String.format("%.0f mAh", live.chargeCounterUah / 1000.0), Ui.textPrimary(this));
        }
        if (live.cycleCount >= 0) Ui.addRow(big, "Charge cycles", String.valueOf(live.cycleCount), Ui.textPrimary(this));
        if (parser != null) {
            BatteryStatsParser.Summary sum = parser.summary();
            if (sum.estimatedCapacityMah > 0) {
                Ui.addRow(big, "Battery capacity", (int) sum.estimatedCapacityMah + " mAh", Ui.textPrimary(this));
            }
            if (sum.timeOnBatteryMs > 0) {
                Ui.addRow(big, "On battery", BatteryStatsParser.formatDuration(sum.timeOnBatteryMs), Ui.textPrimary(this));
            }
            if (sum.dischargeMah > 0) {
                double perHour = sum.dischargeMah / (sum.timeOnBatteryMs / 3600000.0);
                Ui.addRow(big, "Discharge rate", String.format("%.0f mAh/h", perHour),
                        perHour > 900 ? Ui.BAD : perHour > 600 ? Ui.WARN : Ui.GOOD);
            }
            if (!sum.batteryTimeRemaining.isEmpty()) {
                Ui.addRow(big, "Time remaining", sum.batteryTimeRemaining, Ui.textPrimary(this));
            }
        }
        content.addView(big);

        LinearLayout cycles = Ui.card(this, "Battery health & charge cycles");
        if (live.cycleCount >= 0) {
            Ui.addRow(cycles, "Firmware cycle count",
                    live.cycleCount + (live.cycleCount > 0 ? " cycles" : ""), Ui.textPrimary(this));
            if (live.vendorCycleNote != null && live.vendorCycleNote.length() > 0) {
                Ui.addNote(cycles, "Firmware cycle count: " + live.vendorCycleNote + ".");
            }
        } else {
            Ui.addRow(cycles, "Firmware cycle count", "not reported by this firmware", Ui.textSecondary(this));
        }
        double ratedMah = parser != null ? parser.summary().estimatedCapacityMah : 0;
        if (ratedMah > 0) {
            Ui.addRow(cycles, "Rated capacity (kernel)", Math.round(ratedMah) + " mAh", Ui.textPrimary(this));
        }
        HistoryStore.CycleStats cs = history.cycleStats();
        if (cs.samples > 1) {
            Ui.addRow(cycles, "Full charge (peak observed)", Math.round(cs.fullChargeMah) + " mAh", Ui.textPrimary(this));
            Ui.addRow(cycles, "Energy used", Math.round(cs.totalUsedMah) + " mAh", Ui.textPrimary(this));
            Ui.addRow(cycles, "Charge cycles (tracked)", String.format("%.2f", cs.cycles), Ui.textPrimary(this));
            if (cs.avgMahPerCycle > 0) {
                Ui.addRow(cycles, "Average per cycle", Math.round(cs.avgMahPerCycle) + " mAh", Ui.textPrimary(this));
            }
            if (cs.cyclesPerMonth > 0) {
                Ui.addRow(cycles, "Cycles per month", String.format("%.1f", cs.cyclesPerMonth), Ui.textPrimary(this));
            }
            Ui.addNote(cycles, "Tracked over " + String.format("%.1f", cs.days) + " days and "
                    + cs.samples + " snapshots. One cycle = one full charge worth of energy used.");
        } else {
            Ui.addNote(cycles, "Not enough snapshots yet. Keep the app installed (it samples hourly) "
                    + "and the cycle counter will fill in.");
        }
        if (live.firstUseDate != null && live.firstUseDate.length() > 0) {
            Ui.addRow(cycles, "First use (firmware)", live.firstUseDate, Ui.textPrimary(this));
        }
        content.addView(cycles);

        LinearLayout screen = Ui.card(this, "Display");
        Ui.addRow(screen, "Screen", live.screenOn ? "on" : "off", live.screenOn ? Ui.GOOD : Ui.textSecondary(this));
        if (live.brightness > 0) Ui.addRow(screen, "Brightness", live.brightness + " / 255", Ui.textPrimary(this));
        if (live.refreshRate > 0) Ui.addRow(screen, "Refresh rate", Math.round(live.refreshRate) + " Hz", Ui.textPrimary(this));
        content.addView(screen);

        LinearLayout radio = Ui.card(this, "Radios & sensors");
        Ui.addRow(radio, "Network", live.networkType, Ui.textPrimary(this));
        if (live.wifiRssi > -127) Ui.addRow(radio, "Wi-Fi signal", live.wifiRssi + " dBm", Ui.textPrimary(this));
        Ui.addRow(radio, "GPS", live.gpsEnabled ? "enabled" : "disabled", live.gpsEnabled ? Ui.WARN : Ui.textPrimary(this));
        Ui.addRow(radio, "Bluetooth", live.bluetoothOn ? "on" : "off", Ui.textPrimary(this));
        content.addView(radio);

        LinearLayout sys = Ui.card(this, "System");
        if (live.cpuLoad >= 0) Ui.addRow(sys, "CPU load", String.format("%.0f %%", live.cpuLoad),
                live.cpuLoad > 60 ? Ui.WARN : Ui.textPrimary(this));
        if (live.totalMemMb > 0) {
            Ui.addRow(sys, "Memory free", live.availMemMb + " / " + live.totalMemMb + " MB", Ui.textPrimary(this));
        }
        Ui.addRow(sys, "Uptime", BatteryStatsParser.formatDuration(live.uptimeMs), Ui.textPrimary(this));
        content.addView(sys);

        if (!lastError.isEmpty()) {
            LinearLayout err = Ui.card(this, "Last error");
            Ui.addNote(err, lastError);
            content.addView(err);
        }
    }

    private void renderApps() {
        LinearLayout head = Ui.card(this, null);
        if (parser != null) {
            Ui.addNote(head, "Measured with Shizuku: per-app CPU, screen time, radio use and wakelocks "
                    + "since the last charge. The percentage is the app's share of the measured discharge.");
        } else if (!UsageSampler.hasPermission(this)) {
            Ui.addNote(head, "Neither Shizuku nor usage access is available. Tap \"Usage access\" to grant "
                    + "the estimate below, or \"Shizuku\" for measured values.");
        } else {
            Ui.addNote(head, "Shizuku is not connected yet — showing an estimate from Android's usage-stats "
                    + "(foreground time × " + String.format("%.0f", UsageSampler.mahPerMinute(this))
                    + " mAh/min). Grant Shizuku for measured values.");
        }
        content.addView(head);

        if (parser != null) {
            List<BatteryStatsParser.UidStat> list = sortedUids();
            double total = 0;
            for (BatteryStatsParser.UidStat u : list) total += score(u);
            double discharge = parser.summary().dischargeMah;
            for (int i = 0; i < list.size() && i < 25; i++) {
                BatteryStatsParser.UidStat u = list.get(i);
                double share = total > 0 ? score(u) / total : 0;
                double estMah = discharge > 0 ? share * discharge : -1;
                addAppRow(u.label, u.pkg, u, share, estMah);
            }
            if (list.isEmpty()) {
                content.addView(Ui.text(this, "No per-app data in the dump.", 13, Ui.textSecondary(this)));
            }
        } else {
            for (int i = 0; i < usageFallback.size() && i < 25; i++) {
                UsageSampler.AppUsage a = usageFallback.get(i);
                addUsageRow(a);
            }
            if (usageFallback.isEmpty()) {
                LinearLayout c = Ui.card(this, null);
                Ui.addNote(c, "No usage-stats data. Enable \"Usage access\" for the app, then refresh.");
                content.addView(c);
            }
        }
    }

    private void addAppRow(String label, String pkg, BatteryStatsParser.UidStat u, double share, double estMah) {
        Context c = this;
        LinearLayout card = Ui.card(c, null);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.Drawable icon = resolver.iconForUid(u.uid);
        if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            head.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)));
        }
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView l = Ui.text(c, label, 15, Ui.textPrimary(c));
        l.setTypeface(l.getTypeface(), android.graphics.Typeface.BOLD);
        names.addView(l);
        if (pkg != null && pkg.length() > 0 && !pkg.equals(label)) {
            names.addView(Ui.text(c, pkg, 11, Ui.textSecondary(c)));
        }
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String right = String.format("%.1f%%", share * 100);
        if (estMah > 0) right += "\n≈" + Math.round(estMah) + " mAh";
        TextView rv = Ui.text(c, right, 14, Ui.textPrimary(c));
        rv.setGravity(Gravity.END);
        head.addView(rv);
        card.addView(head);

        if (u.cpu > 0) Ui.addBar(card, "CPU", Math.min(1, u.cpu / 100.0), Ui.ACCENT,
                String.format("%.0f%% of battery time (multi-core)", u.cpu));
        if (u.screen > 0) Ui.addBar(card, "Screen on / foreground", Math.min(1, u.screen / 100.0), Ui.GOOD, String.format("%.1f%%", u.screen));
        if (u.wifi > 0) Ui.addBar(card, "Wi-Fi", Math.min(1, u.wifi / 100.0), Ui.WARN, String.format("%.1f%%", u.wifi));
        if (u.mobile > 0) Ui.addBar(card, "Mobile data", Math.min(1, u.mobile / 100.0), Ui.WARN, String.format("%.1f%%", u.mobile));
        if (u.audio > 0) Ui.addBar(card, "Audio", Math.min(1, u.audio / 100.0), Ui.BAD, String.format("%.1f%%", u.audio));
        if (u.sensors > 0) Ui.addBar(card, "Sensors", Math.min(1, u.sensors / 100.0), Ui.BAD, String.format("%.1f%%", u.sensors));

        StringBuilder extra = new StringBuilder();
        if (u.fgMs > 0) extra.append("foreground ").append(BatteryStatsParser.formatDuration(u.fgMs));
        if (u.fgsMs > 0) extra.append(extra.length() > 0 ? " · " : "").append("service ")
                .append(BatteryStatsParser.formatDuration(u.fgsMs));
        if (u.bgMs > 0) extra.append(extra.length() > 0 ? " · " : "").append("background ")
                .append(BatteryStatsParser.formatDuration(u.bgMs));
        if (u.cachedMs > 0) extra.append(extra.length() > 0 ? " · " : "").append("cached ")
                .append(BatteryStatsParser.formatDuration(u.cachedMs));
        if (u.wakelockMs > 0) extra.append(extra.length() > 0 ? " · " : "").append("wakelock ")
                .append(BatteryStatsParser.formatDuration(u.wakelockMs));
        if (u.wakeupCount > 0) extra.append(extra.length() > 0 ? " · " : "")
                .append(u.wakeupCount).append(u.wakeupCount == 1 ? " wakeup" : " wakeups");
        if (extra.length() > 0) Ui.addNote(card, extra.toString());
        content.addView(card);
    }

    private void addUsageRow(UsageSampler.AppUsage a) {
        Context c = this;
        LinearLayout card = Ui.card(c, null);
        TextView l = Ui.text(c, a.label, 15, Ui.textPrimary(c));
        l.setTypeface(l.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(l);
        if (!a.pkg.equals(a.label)) card.addView(Ui.text(c, a.pkg, 11, Ui.textSecondary(c)));
        Ui.addBar(card, "Foreground", Math.min(1, a.foregroundMs / 3600000.0), Ui.ACCENT,
                BatteryStatsParser.formatDuration(a.foregroundMs));
        Ui.addNote(card, String.format("estimated ≈ %.0f mAh (screen-on assumption, not a measurement)",
                a.estMah));
        content.addView(card);
    }

    private void renderHardware() {
        if (parser == null) {
            LinearLayout c = Ui.card(this, null);
            Ui.addNote(c, "Hardware attribution needs Shizuku. Grant the permission and refresh.");
            content.addView(c);
            return;
        }
        BatteryStatsParser.Summary sum = parser.summary();
        BatteryStatsParser.Subsystems sub = parser.subsystems();

        LinearLayout screen = Ui.card(this, "Display");
        if (sum.timeOnBatteryMs > 0) {
            double onRatio = (double) sum.screenOnMs / sum.timeOnBatteryMs;
            Ui.addBar(screen, "Screen on", onRatio, onRatio > 0.8 ? Ui.BAD : Ui.ACCENT,
                    String.format("%.0f%% of the time", onRatio * 100));
        }
        if (sum.screenOnDischargeMah > 0 || sum.screenOffDischargeMah > 0) {
            Ui.addRow(screen, "Discharge with screen on", Math.round(sum.screenOnDischargeMah) + " mAh", Ui.textPrimary(this));
            Ui.addRow(screen, "Discharge with screen off", Math.round(sum.screenOffDischargeMah) + " mAh", Ui.textPrimary(this));
        }
        for (Map.Entry<String, Long> e : sub.brightness.entrySet()) {
            Ui.addRow(screen, "Brightness " + e.getKey(), BatteryStatsParser.formatDuration(e.getValue()), Ui.textPrimary(this));
        }
        for (Map.Entry<String, Long> e : sub.refreshRates.entrySet()) {
            Ui.addRow(screen, "Refresh rate " + e.getKey(), BatteryStatsParser.formatDuration(e.getValue()), Ui.textPrimary(this));
        }
        content.addView(screen);

        LinearLayout wifi = Ui.card(this, "Wi-Fi");
        if (sum.timeOnBatteryMs > 0) {
            double active = (double) sub.wifiActiveMs / sum.timeOnBatteryMs;
            Ui.addBar(wifi, "Radio active", active, active > 0.5 ? Ui.WARN : Ui.ACCENT,
                    String.format("%.0f%% of the time", active * 100));
        }
        Ui.addRow(wifi, "Scan time", BatteryStatsParser.formatDuration(sub.wifiScanMs), Ui.textPrimary(this));
        Ui.addRow(wifi, "Sleep time", BatteryStatsParser.formatDuration(sub.wifiSleepMs), Ui.textPrimary(this));
        content.addView(wifi);

        LinearLayout cell = Ui.card(this, "Mobile network");
        if (sum.timeOnBatteryMs > 0) {
            double active = (double) sub.cellActiveMs / sum.timeOnBatteryMs;
            Ui.addBar(cell, "Radio active", active, active > 0.3 ? Ui.WARN : Ui.ACCENT,
                    String.format("%.0f%% of the time", active * 100));
        }
        Ui.addRow(cell, "Rx time", BatteryStatsParser.formatDuration(sub.cellRxMs), Ui.textPrimary(this));
        for (Map.Entry<String, Long> e : sub.cellSignal.entrySet()) {
            Ui.addRow(cell, "Signal " + e.getKey(), BatteryStatsParser.formatDuration(e.getValue()), Ui.textPrimary(this));
        }
        Ui.addRow(cell, "Connectivity changes", String.valueOf(sub.connectivityChanges), Ui.textPrimary(this));
        content.addView(cell);

        LinearLayout gps = Ui.card(this, "GPS");
        Ui.addRow(gps, "Poor signal (< 20 dBHz)", BatteryStatsParser.formatDuration(sub.gpsPoorMs), Ui.textPrimary(this));
        Ui.addRow(gps, "Good signal (> 20 dBHz)", BatteryStatsParser.formatDuration(sub.gpsGoodMs), Ui.textPrimary(this));
        if (sub.gpsPoorMs + sub.gpsGoodMs == 0) {
            Ui.addNote(gps, "GPS was not in use during the measured period.");
        }
        content.addView(gps);

        LinearLayout bt = Ui.card(this, "Bluetooth");
        Ui.addRow(bt, "Scan time", BatteryStatsParser.formatDuration(sub.btScanMs), Ui.textPrimary(this));
        Ui.addRow(bt, "Rx time", BatteryStatsParser.formatDuration(sub.btRxMs), Ui.textPrimary(this));
        Ui.addRow(bt, "Idle time", BatteryStatsParser.formatDuration(sub.btIdleMs), Ui.textPrimary(this));
        if (sub.btDrainMah > 0) Ui.addRow(bt, "Reported drain", String.format("%.2f mAh", sub.btDrainMah), Ui.textPrimary(this));
        content.addView(bt);

        LinearLayout wake = Ui.card(this, "Wakelocks & wakeups");
        Ui.addRow(wake, "Full wakelock time", BatteryStatsParser.formatDuration(sub.fullWakeMs), Ui.textPrimary(this));
        Ui.addRow(wake, "Partial wakelock time", BatteryStatsParser.formatDuration(sub.partialWakeMs), Ui.textPrimary(this));
        Ui.addRow(wake, "Wi-Fi multicast wakelock", BatteryStatsParser.formatDuration(sub.wifiMulticastWakeMs), Ui.textPrimary(this));
        Ui.addNote(wake, "Top kernel wakelocks:");
        List<Map.Entry<String, Long>> kw = new ArrayList<Map.Entry<String, Long>>(sub.kernelWakelockMs.entrySet());
        Collections.sort(kw, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        for (int i = 0; i < kw.size() && i < 8; i++) {
            Ui.addRow(wake, kw.get(i).getKey(), BatteryStatsParser.formatDuration(kw.get(i).getValue()), Ui.textPrimary(this));
        }
        if (!sub.wakeupAttribution.isEmpty()) {
            Ui.addNote(wake, "Wakeup sources (count):");
            List<Map.Entry<String, Integer>> wa = new ArrayList<Map.Entry<String, Integer>>(sub.wakeupAttribution.entrySet());
            Collections.sort(wa, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            for (int i = 0; i < wa.size() && i < 8; i++) {
                int uid = Integer.parseInt(wa.get(i).getKey());
                Ui.addRow(wake, resolver.labelForUid(uid), wa.get(i).getValue() + " wakeups", Ui.textPrimary(this));
            }
        }
        content.addView(wake);
    }

    private void renderHistory() {
        long day = System.currentTimeMillis() - 24 * 3600 * 1000L;
        List<HistoryStore.Snapshot> snaps = history.listSince(day);
        LinearLayout card = Ui.card(this, "Discharge rate (last 24 h)");
        double[] values = new double[snaps.size()];
        String[] labels = new String[snaps.size()];
        for (int i = 0; i < snaps.size(); i++) {
            values[i] = snaps.get(i).mahPerHour;
            labels[i] = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(snaps.get(i).ts));
        }
        Ui.ChartView chart = new Ui.ChartView(this);
        chart.setData(values, labels, Ui.ACCENT);
        card.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 120)));
        double avg = history.averageMahPerHour(day);
        if (avg > 0) {
            Ui.addRow(card, "Average", String.format("%.0f mAh/h", avg), avg > 900 ? Ui.BAD : Ui.textPrimary(this));
            double hoursLeft = avg > 0 ? (live != null && live.chargeCounterUah > 0 ? live.chargeCounterUah / 1000.0 / avg : -1) : -1;
            if (hoursLeft > 0) {
                Ui.addRow(card, "Expected screen-on time left", String.format("%.1f h", hoursLeft), Ui.textPrimary(this));
            }
        }
        content.addView(card);

        LinearLayout list = Ui.card(this, "Snapshots");
        if (snaps.isEmpty()) {
            Ui.addNote(list, "No snapshots yet. Open the app regularly, or keep it installed so the "
                    + "background sampler can record.");
        }
        for (int i = snaps.size() - 1; i >= 0 && i >= snaps.size() - 12; i--) {
            HistoryStore.Snapshot s = snaps.get(i);
            String time = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(s.ts));
            String v = s.level >= 0 ? s.level + "%" : "?";
            if (s.mahPerHour > 0) v += String.format("  ·  %.0f mAh/h", s.mahPerHour);
            if (s.topApp != null && s.topApp.length() > 0) v += "  ·  " + s.topApp;
            Ui.addRow(list, time, v, Ui.textPrimary(this));
        }
        content.addView(list);

        LinearLayout actions = Ui.card(this, "Export");
        Ui.addNote(actions, "Files are written to the app's external files folder so they can be "
                + "opened with a file manager or a PC.");
        actions.addView(button("Write CSV", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportCsv();
            }
        }));
        actions.addView(button("Save batterystats dump", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportRaw();
            }
        }));
        content.addView(actions);
    }

    // ------------------------------------------------------------- export

    private File exportDir() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File out = new File(dir, "exports");
        if (!out.exists()) out.mkdirs();
        return out;
    }

    private void exportAll() {
        exportCsv();
        exportRaw();
    }

    private void exportCsv() {
        try {
            File f = new File(exportDir(), "drainscope-history.csv");
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write("timestamp,iso,level,charge_uah,mah_per_hour,screen_on_ratio,top_app,top_pct,source\n");
            for (HistoryStore.Snapshot s : history.listSince(0)) {
                w.write(s.ts + "," + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(s.ts))
                        + "," + s.level + "," + s.chargeUah + "," + s.mahPerHour + "," + s.screenOnRatio
                        + "," + "\"" + (s.topApp == null ? "" : s.topApp.replace("\"", "'")) + "\"," + s.topPct + "," + s.source + "\n");
            }
            w.close();
            toast("CSV written to " + f.getAbsolutePath());
        } catch (Throwable t) {
            toast("CSV export failed: " + t.getMessage());
        }
    }

    private void exportRaw() {
        if (rawDump.isEmpty()) {
            toast("No batterystats dump available (connect Shizuku first)");
            return;
        }
        try {
            File f = new File(exportDir(), "batterystats.txt");
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(rawDump);
            w.close();
            toast("Dump written to " + f.getAbsolutePath()
                    + " (open with Google Battery Historian)");
        } catch (Throwable t) {
            toast("Export failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ helpers

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void openUsageAccessSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onShizukuStateChanged(String state) {
        updateStatus();
        if ("connected".equals(state)) {
            refresh();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }
}
