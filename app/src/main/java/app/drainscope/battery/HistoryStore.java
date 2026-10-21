package app.drainscope.battery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Local history of battery snapshots, used to spot slow drains over days. */
public class HistoryStore extends SQLiteOpenHelper {

    private static final String DB = "drainscope.db";

    public static class Snapshot {
        public long ts;
        public int level = -1;
        public long chargeUah = -1;
        public double mahPerHour = -1;
        public double screenOnRatio = -1;
        public String topApp = "";
        public double topPct = 0;
        public String source = "";
    }

    public HistoryStore(Context ctx) {
        super(ctx, DB, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE snapshots (ts INTEGER PRIMARY KEY, level INTEGER, charge_uah INTEGER,"
                + " mah_per_hour REAL, screen_on_ratio REAL, top_app TEXT, top_pct REAL, source TEXT)");
        db.execSQL("CREATE TABLE offenders (ts INTEGER, pkg TEXT, label TEXT, cpu REAL, screen REAL,"
                + " wifi REAL, wakelock_ms INTEGER)");
        db.execSQL("CREATE INDEX idx_off_ts ON offenders(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS snapshots");
        db.execSQL("DROP TABLE IF EXISTS offenders");
        onCreate(db);
    }

    public void insert(Snapshot s) {
        try {
            if (s.mahPerHour <= 0 && s.chargeUah > 0) {
                s.mahPerHour = rateFromChargeCounter(s);
            }
            ContentValues v = new ContentValues();
            v.put("ts", s.ts);
            v.put("level", s.level);
            v.put("charge_uah", s.chargeUah);
            v.put("mah_per_hour", s.mahPerHour);
            v.put("screen_on_ratio", s.screenOnRatio);
            v.put("top_app", s.topApp);
            v.put("top_pct", s.topPct);
            v.put("source", s.source);
            getWritableDatabase().insertWithOnConflict("snapshots", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable ignored) {
        }
    }

    /** Derives mAh/h from the difference between two charge-counter readings. */
    private double rateFromChargeCounter(Snapshot s) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, charge_uah FROM snapshots WHERE charge_uah > 0 AND ts < ? ORDER BY ts DESC LIMIT 1",
                    new String[]{String.valueOf(s.ts)});
            if (c.moveToFirst()) {
                long prevTs = c.getLong(0);
                long prevUah = c.getLong(1);
                double hours = (s.ts - prevTs) / 3600000.0;
                double mah = (prevUah - s.chargeUah) / 1000.0;
                if (hours > 0.01 && mah >= 0) return mah / hours;
            }
        } catch (Throwable ignored) {
        } finally {
            close(c);
        }
        return -1;
    }

    public void insertOffenders(long ts, List<BatteryStatsParser.UidStat> stats) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            for (BatteryStatsParser.UidStat u : stats) {
                ContentValues v = new ContentValues();
                v.put("ts", ts);
                v.put("pkg", u.label);
                v.put("label", u.label);
                v.put("cpu", u.cpu);
                v.put("screen", u.screen);
                v.put("wifi", u.wifi);
                v.put("wakelock_ms", u.wakelockMs);
                db.insert("offenders", null, v);
            }
            db.setTransactionSuccessful();
        } catch (Throwable ignored) {
        } finally {
            try {
                getWritableDatabase().endTransaction();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Aggregate charge-cycle information derived from the recorded snapshots. */
    public static class CycleStats {
        public long firstTs, lastTs;
        public int samples;
        public double totalUsedMah;
        public double fullChargeMah;
        public double cycles;
        public double days;
        public double cyclesPerMonth;
        public double avgMahPerCycle;
    }

    public CycleStats cycleStats() {
        CycleStats st = new CycleStats();
        List<Snapshot> l = listSince(0);
        if (l.isEmpty()) return st;
        st.firstTs = l.get(0).ts;
        st.lastTs = l.get(l.size() - 1).ts;
        st.samples = l.size();
        long maxCharge = 0;
        for (Snapshot s : l) {
            if (s.chargeUah > maxCharge) maxCharge = s.chargeUah;
            if (s.chargeUah > 0 && s.chargeUah / 1000.0 > st.fullChargeMah) {
                st.fullChargeMah = s.chargeUah / 1000.0;
            }
        }
        for (int i = 1; i < l.size(); i++) {
            long a = l.get(i - 1).chargeUah, b = l.get(i).chargeUah;
            if (a > 0 && b > 0 && a > b) {
                st.totalUsedMah += (a - b) / 1000.0;   // only discharging counts
            }
        }
        st.days = (st.lastTs - st.firstTs) / 86400000.0;
        if (st.fullChargeMah > 0) {
            st.cycles = st.totalUsedMah / st.fullChargeMah;
        }
        if (st.cycles > 0) {
            st.avgMahPerCycle = st.totalUsedMah / st.cycles;
            if (st.days > 0.5) {
                st.cyclesPerMonth = st.cycles / (st.days / 30.0);
            }
        }
        return st;
    }

    public List<Snapshot> listSince(long since) {
        List<Snapshot> out = new ArrayList<Snapshot>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, level, charge_uah, mah_per_hour, screen_on_ratio, top_app, top_pct, source"
                            + " FROM snapshots WHERE ts >= ? ORDER BY ts ASC", new String[]{String.valueOf(since)});
            while (c.moveToNext()) {
                Snapshot s = new Snapshot();
                s.ts = c.getLong(0);
                s.level = c.getInt(1);
                s.chargeUah = c.getLong(2);
                s.mahPerHour = c.getDouble(3);
                s.screenOnRatio = c.getDouble(4);
                s.topApp = c.getString(5);
                s.topPct = c.getDouble(6);
                s.source = c.getString(7);
                out.add(s);
            }
        } catch (Throwable ignored) {
        } finally {
            close(c);
        }
        return out;
    }

    /** Average discharge rate per hour over the recorded history. */
    public double averageMahPerHour(long since) {
        List<Snapshot> l = listSince(since);
        if (l.size() < 2) return -1;
        Snapshot first = l.get(0), last = l.get(l.size() - 1);
        double hours = (last.ts - first.ts) / 3600000.0;
        if (hours <= 0) return -1;
        double mah = 0;
        boolean any = false;
        for (Snapshot s : l) {
            if (s.mahPerHour > 0) {
                mah += s.mahPerHour;
                any = true;
            }
        }
        if (!any) return -1;
        return mah / l.size();
    }

    private void close(Cursor c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) {
        }
    }
}
