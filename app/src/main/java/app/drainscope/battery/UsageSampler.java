package app.drainscope.battery;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rootless fallback: estimates which apps drained the battery from the
 * system's usage-stats permission (foreground time), which every Android
 * version exposes without Shizuku or root.
 *
 * The milliamp-hour figures are an estimate derived from foreground time and
 * are clearly marked as such in the UI.
 */
public class UsageSampler {

    /** Rough screen-on discharge of a typical phone, in mAh per minute. */
    public static final double DEFAULT_MAH_PER_MINUTE = 8.0;

    public static class AppUsage {
        public String pkg = "";
        public String label = "";
        public long foregroundMs;
        public long fgsMs;
        public double estMah;
    }

    public static boolean hasPermission(Context ctx) {
        try {
            AppOpsManager am = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (am == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = am.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.getPackageName());
            } else {
                mode = am.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<AppUsage> sample(Context ctx, long sinceMs) {
        List<AppUsage> out = new ArrayList<AppUsage>();
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        long start = Math.max(sinceMs, now - 7L * 24 * 3600 * 1000);

        Map<String, Long> fg = new HashMap<String, Long>();
        Map<String, Long> fgs = new HashMap<String, Long>();
        try {
            UsageEvents events = usm.queryEvents(start, now);
            UsageEvents.Event e = new UsageEvents.Event();
            Map<String, Long> fgStart = new HashMap<String, Long>();
            Map<String, Long> fgsStart = new HashMap<String, Long>();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                int type = e.getEventType();
                String pkg = e.getPackageName();
                if (pkg == null) continue;
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    fgStart.put(pkg, e.getTimeStamp());
                } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                        || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    Long s = fgStart.remove(pkg);
                    if (s != null) {
                        Long cur = fg.get(pkg);
                        fg.put(pkg, (cur == null ? 0L : cur) + (e.getTimeStamp() - s));
                    }
                } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                    fgsStart.put(pkg, e.getTimeStamp());
                } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_STOP) {
                    Long s = fgsStart.remove(pkg);
                    if (s != null) {
                        Long cur = fgs.get(pkg);
                        fgs.put(pkg, (cur == null ? 0L : cur) + (e.getTimeStamp() - s));
                    }
                }
            }
            for (Map.Entry<String, Long> en : fgStart.entrySet()) {
                Long cur = fg.get(en.getKey());
                fg.put(en.getKey(), (cur == null ? 0L : cur) + (now - en.getValue()));
            }
            for (Map.Entry<String, Long> en : fgsStart.entrySet()) {
                Long cur = fgs.get(en.getKey());
                fgs.put(en.getKey(), (cur == null ? 0L : cur) + (now - en.getValue()));
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now);
                for (UsageStats s : stats) {
                    long t = s.getTotalTimeInForeground();
                    if (t > 0) {
                        Long cur = fg.get(s.getPackageName());
                        fg.put(s.getPackageName(), Math.max(cur == null ? 0L : cur, t));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        PackageManager pm = ctx.getPackageManager();
        for (Map.Entry<String, Long> en : fg.entrySet()) {
            AppUsage u = new AppUsage();
            u.pkg = en.getKey();
            u.foregroundMs = en.getValue();
            Long f = fgs.get(en.getKey());
            u.fgsMs = f == null ? 0L : f;
            u.estMah = (u.foregroundMs / 60000.0) * mahPerMinute(ctx);
            try {
                u.label = pm.getApplicationLabel(pm.getApplicationInfo(u.pkg, 0)).toString();
            } catch (Exception ignored) {
                u.label = u.pkg;
            }
            out.add(u);
        }
        Collections.sort(out, new Comparator<AppUsage>() {
            @Override
            public int compare(AppUsage a, AppUsage b) {
                return Long.compare(b.foregroundMs, a.foregroundMs);
            }
        });
        return out;
    }

    public static double mahPerMinute(Context ctx) {
        try {
            float v = ctx.getSharedPreferences("drainscope", Context.MODE_PRIVATE)
                    .getFloat("mah_per_minute", (float) DEFAULT_MAH_PER_MINUTE);
            return v > 0 ? v : DEFAULT_MAH_PER_MINUTE;
        } catch (Throwable t) {
            return DEFAULT_MAH_PER_MINUTE;
        }
    }
}
