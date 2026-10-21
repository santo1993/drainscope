package app.drainscope.battery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the text output of {@code dumpsys batterystats} into structured data.
 *
 * The format is undocumented and changes between Android releases, so every
 * value is optional: a missing section simply leaves the field at its default.
 */
public class BatteryStatsParser {

    public static class UidStat {
        public int uid = -1;
        public String uidStr = "";
        public String label = "";
        public String pkg = "";
        public double cpu;            // % of battery time spent running
        public double screen;         // % attributed to screen on-time
        public double wifi;
        public double audio;
        public double sensors;
        public double bluetooth;
        public double mobile;
        public long fgMs, bgMs, fgsMs, cachedMs;
        public long wakelockMs;
        public int wakeupCount;
        public long networkRxBytes, networkTxBytes;

        public double total() {
            double t = cpu;
            if (screen > t) t = screen;
            if (wifi > t) t = wifi;
            if (mobile > t) t = mobile;
            return t;
        }
    }

    public static class Summary {
        public long timeOnBatteryMs;
        public long screenOnMs;
        public long screenOffMs;
        public double dischargeMah;
        public double screenOnDischargeMah;
        public double screenOffDischargeMah;
        public double estimatedCapacityMah;
        public String batteryTimeRemaining = "";
        public String startClock = "";
        public int dischargeSteps;
    }

    public static class Subsystems {
        public long cellActiveMs, cellRxMs, cellTxMs, cellSleepMs, cellIdleMs;
        public long wifiActiveMs, wifiScanMs, wifiSleepMs;
        public long btScanMs, btRxMs, btTxMs, btIdleMs;
        public double btDrainMah;
        public long gpsPoorMs, gpsGoodMs;
        public long fullWakeMs, partialWakeMs, wifiMulticastWakeMs;
        public int connectivityChanges;
        public final Map<String, Long> brightness = new LinkedHashMap<String, Long>();
        public final Map<String, Long> refreshRates = new LinkedHashMap<String, Long>();
        public final List<String> kernelWakelocks = new ArrayList<String>();
        public final Map<String, Long> kernelWakelockMs = new LinkedHashMap<String, Long>();
        public final Map<String, Integer> wakeupAttribution = new LinkedHashMap<String, Integer>();
        public final Map<String, Long> cellSignal = new LinkedHashMap<String, Long>();
    }

    private static final Pattern DURATION = Pattern.compile("(-?\\d+d)?\\s*(-?\\d+h)?\\s*(-?\\d+m)?\\s*(-?\\d+s)?\\s*(-?\\d+ms)?");
    private static final Pattern DURATION_UNIT = Pattern.compile("(\\d+)(ms|h|m|s|d)");
    private static final Pattern UID_LINE = Pattern.compile("^\\s*UID\\s+(\\S+?):\\s*([0-9.]+)(.*)$");
    private static final Pattern ATTRIBUTION = Pattern.compile("Attribution:\\s*\\w+\\s*\\[([^\\]]*)\\]");

    private final String text;
    private final Summary summary = new Summary();
    private final Subsystems subsystems = new Subsystems();
    private final List<UidStat> uids = new ArrayList<UidStat>();
    private UidStat current;

    public BatteryStatsParser(String text) {
        this.text = text == null ? "" : text;
        parse();
    }

    public Summary summary() { return summary; }
    public Subsystems subsystems() { return subsystems; }
    public List<UidStat> uids() { return uids; }

    private void parse() {
        String[] lines = text.split("\n");
        boolean inPartialWakeLocks = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();

            Matcher m = UID_LINE.matcher(line);
            if (m.matches()) {
                current = new UidStat();
                current.uidStr = m.group(1);
                current.uid = parseUid(m.group(1));
                current.cpu = parseDouble(m.group(2));
                parseStateTimes(current, m.group(3));
                uids.add(current);
                // the next line holds the per-subsystem breakdown
                if (i + 1 < lines.length) {
                    parseBreakdown(current, lines[i + 1].trim());
                }
                continue;
            }

            if (t.startsWith("All partial wake locks") || t.startsWith("All full wake locks")) {
                inPartialWakeLocks = true;
                continue;
            }
            if (inPartialWakeLocks) {
                if (t.startsWith("Wake lock ")) {
                    parseWakeLock(t);
                    continue;
                }
                if (t.isEmpty() || t.startsWith("Kernel Wake lock")) {
                    inPartialWakeLocks = false;
                }
            }

            if (t.startsWith("Kernel Wake lock ")) {
                String name = t.substring("Kernel Wake lock ".length());
                long ms = 0;
                int sp = name.indexOf(": ");
                if (sp > 0) {
                    ms = parseFirstDuration(name.substring(sp + 2));
                    name = name.substring(0, sp);
                }
                subsystems.kernelWakelocks.add(name);
                Long prev = subsystems.kernelWakelockMs.get(name);
                subsystems.kernelWakelockMs.put(name, (prev == null ? 0L : prev) + ms);
                continue;
            }

            Matcher attr = ATTRIBUTION.matcher(t);
            if (attr.find()) {
                // "Attribution: Alarm [1000 PER , u0a272 BFGS]"
                String body = attr.group(1);
                for (String part : body.split(",")) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    String first = p.split("\\s+")[0];
                    if (first.matches("u\\d+a\\d+") || first.matches("\\d+")) {
                        int uid = parseUid(first);
                        Integer c = subsystems.wakeupAttribution.get(uidKey(uid));
                        subsystems.wakeupAttribution.put(uidKey(uid), (c == null ? 0 : c) + 1);
                        for (UidStat u : uids) {
                            if (u.uid == uid) u.wakeupCount++;
                        }
                    }
                }
                continue;
            }

            parseSummaryLine(t);
            parseSubsystemLine(t);
        }
    }

    private void parseSummaryLine(String t) {
        if (t.startsWith("Time on battery:")) {
            summary.timeOnBatteryMs = parseFirstDuration(t.substring("Time on battery:".length()).trim());
        } else if (t.startsWith("Time on battery screen off:")) {
            summary.screenOffMs = parseFirstDuration(t.substring("Time on battery screen off:".length()).trim());
        } else if (t.startsWith("Screen on:")) {
            summary.screenOnMs = parseFirstDuration(t.substring("Screen on:".length()).trim());
        } else if (t.startsWith("Estimated battery capacity:")) {
            summary.estimatedCapacityMah = parseDouble(t.substring("Estimated battery capacity:".length()));
        } else if (t.startsWith("Discharge:")) {
            summary.dischargeMah = parseDouble(t.substring("Discharge:".length()));
        } else if (t.startsWith("Screen on discharge:")) {
            summary.screenOnDischargeMah = parseDouble(t.substring("Screen on discharge:".length()));
        } else if (t.startsWith("Screen off discharge:")) {
            summary.screenOffDischargeMah = parseDouble(t.substring("Screen off discharge:".length()));
        } else if (t.startsWith("Battery time remaining:")) {
            summary.batteryTimeRemaining = t.substring("Battery time remaining:".length()).trim();
        } else if (t.startsWith("Start clock time:")) {
            summary.startClock = t.substring("Start clock time:".length()).trim();
        } else if (t.startsWith("Connectivity changes:")) {
            subsystems.connectivityChanges = (int) parseDouble(t.substring("Connectivity changes:".length()));
        } else if (t.startsWith("Total full wakelock time:")) {
            subsystems.fullWakeMs = parseFirstDuration(t.substring("Total full wakelock time:".length()));
        } else if (t.startsWith("Total partial wakelock time:")) {
            subsystems.partialWakeMs = parseFirstDuration(t.substring("Total partial wakelock time:".length()));
        } else if (t.startsWith("Total WiFi Multicast wakelock time:")) {
            subsystems.wifiMulticastWakeMs = parseFirstDuration(t.substring("Total WiFi Multicast wakelock time:".length()));
        } else if (t.startsWith("Discharge step")) {
            summary.dischargeSteps++;
        } else if (t.startsWith("dark ") || t.startsWith("light ") || t.startsWith("dim ")) {
            long ms = parseFirstDuration(t);
            if (ms > 0) subsystems.brightness.put(t.split("\\s+")[0], ms);
        } else if (t.startsWith("low ") || t.startsWith("high ") || t.startsWith("medium ")
                || t.startsWith("very high ") || t.startsWith("refresh ")) {
            long ms = parseFirstDuration(t);
            if (ms > 0) subsystems.refreshRates.put(t.split("\\s+")[0], ms);
        }
    }

    private void parseSubsystemLine(String t) {
        if (t.startsWith("Mobile active time:")) {
            subsystems.cellActiveMs = parseFirstDuration(t.substring("Mobile active time:".length()));
        } else if (t.startsWith("Cellular kernel active time:")) {
            subsystems.cellActiveMs = parseFirstDuration(t.substring("Cellular kernel active time:".length()));
        } else if (t.startsWith("Cellular Rx time:")) {
            subsystems.cellRxMs = parseFirstDuration(t.substring("Cellular Rx time:".length()));
        } else if (t.startsWith("Cellular Sleep time:")) {
            subsystems.cellSleepMs = parseFirstDuration(t.substring("Cellular Sleep time:".length()));
        } else if (t.startsWith("Cellular Idle time:")) {
            subsystems.cellIdleMs = parseFirstDuration(t.substring("Cellular Idle time:".length()));
        } else if (t.startsWith("Wifi kernel active time:")) {
            subsystems.wifiActiveMs = parseFirstDuration(t.substring("Wifi kernel active time:".length()));
        } else if (t.startsWith("WiFi Scan time:")) {
            subsystems.wifiScanMs = parseFirstDuration(t.substring("WiFi Scan time:".length()));
        } else if (t.startsWith("WiFi Sleep time:")) {
            subsystems.wifiSleepMs = parseFirstDuration(t.substring("WiFi Sleep time:".length()));
        } else if (t.startsWith("Bluetooth scan time:")) {
            subsystems.btScanMs = parseFirstDuration(t.substring("Bluetooth scan time:".length()));
        } else if (t.startsWith("Bluetooth Rx time:")) {
            subsystems.btRxMs = parseFirstDuration(t.substring("Bluetooth Rx time:".length()));
        } else if (t.startsWith("Bluetooth Tx time:")) {
            subsystems.btTxMs = parseFirstDuration(t.substring("Bluetooth Tx time:".length()));
        } else if (t.startsWith("Bluetooth Idle time:")) {
            subsystems.btIdleMs = parseFirstDuration(t.substring("Bluetooth Idle time:".length()));
        } else if (t.startsWith("Bluetooth Battery drain:")) {
            subsystems.btDrainMah = parseDouble(t.substring("Bluetooth Battery drain:".length()));
        } else if (t.startsWith("poor (less than 20 dBHz)")) {
            subsystems.gpsPoorMs = parseFirstDuration(t.substring("poor (less than 20 dBHz):".length()));
        } else if (t.startsWith("good (greater than 20 dBHz)")) {
            subsystems.gpsGoodMs = parseFirstDuration(t.substring("good (greater than 20 dBHz):".length()));
        } else if (t.contains("dBm):") && t.contains("%") && t.indexOf("RSRP") < 0) {
            // cellular signal strength bucket, e.g. "poor (less than -118dBm):  21m 29s 699ms (99.4%)"
            String key = t.substring(0, t.indexOf('(')).trim();
            long ms = parseFirstDuration(t.substring(t.indexOf(')') + 1));
            subsystems.cellSignal.put(key, ms);
        }
    }

    private void parseWakeLock(String t) {
        // "Wake lock u0a680 harness:agent: 768ms (0 times) max=1742 actual=1742 (running for 1742ms) realtime"
        String rest = t.substring("Wake lock ".length());
        int sp = rest.indexOf(' ');
        if (sp <= 0) return;
        String uidStr = rest.substring(0, sp);
        long ms = parseFirstDuration(rest.substring(sp + 1));
        int uid = parseUid(uidStr);
        for (UidStat u : uids) {
            if (u.uid == uid) {
                u.wakelockMs += ms;
                break;
            }
        }
        UidStat st = null;
        for (UidStat u : uids) {
            if (u.uid == uid) {
                st = u;
                break;
            }
        }
        if (st == null) {
            st = new UidStat();
            st.uid = uid;
            st.uidStr = uidStr;
            uids.add(st);
        }
        st.wakelockMs += ms;
    }

    private void parseStateTimes(UidStat u, String tail) {
        // "fg: 30.4 (16m 19s 52ms) bg: 0.167 (1s 806ms) fgs: 3.32 (5m 18s 952ms) cached: 1.82 (14m 10s 450ms)"
        Matcher mm = Pattern.compile("(fg|bg|fgs|cached|fg-sf|bg-sf):\\s*([0-9.]*)\\s*\\(([^)]*)\\)").matcher(tail);
        while (mm.find()) {
            String key = mm.group(1);
            long ms = parseFirstDuration(mm.group(3));
            if ("fg".equals(key)) u.fgMs = ms;
            else if ("bg".equals(key)) u.bgMs = ms;
            else if ("fgs".equals(key)) u.fgsMs = ms;
            else if ("cached".equals(key)) u.cachedMs = ms;
        }
    }

    private void parseBreakdown(UidStat u, String line) {
        if (line.isEmpty() || line.startsWith("(")) return;
        String[] tokens = line.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.startsWith("(")) continue;
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String key = tok.substring(0, eq);
            String val = tok.substring(eq + 1);
            if (!val.matches("[0-9.]+")) continue;
            double d = parseDouble(val);
            if ("screen".equals(key)) u.screen = d;
            else if ("cpu".equals(key)) u.cpu = Math.max(u.cpu, d);
            else if ("wifi".equals(key)) u.wifi = d;
            else if ("audio".equals(key)) u.audio = d;
            else if ("sensors".equals(key)) u.sensors = d;
            else if ("bluetooth".equals(key)) u.bluetooth = d;
            else if ("mobile".equals(key)) u.mobile = d;
        }
        // network byte counters look like "wifi=1.2MB (1234B)" / "mobile=0B"
        Matcher nb = Pattern.compile("(mobile|wifi|bt)=([0-9.]+)([KMG]?B)\\s*\\(([0-9]+)B\\)").matcher(line);
        while (nb.find()) {
            long bytes = Long.parseLong(nb.group(4));
            if ("mobile".equals(nb.group(1)) || "wifi".equals(nb.group(1))) {
                u.networkRxBytes += bytes;
            }
        }
    }

    public static int parseUid(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        Matcher m = Pattern.compile("^u(\\d+)a(\\d+)$").matcher(s);
        if (m.matches()) {
            int user = Integer.parseInt(m.group(1));
            int appId = Integer.parseInt(m.group(2));
            return user * 100000 + 10000 + appId;
        }
        return -1;
    }

    public static String uidKey(int uid) {
        return uid >= 0 ? String.valueOf(uid) : "unknown";
    }

    public static double parseDouble(String s) {
        if (s == null) return 0;
        s = s.trim();
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        try {
            return Double.parseDouble(s.substring(0, i));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Parses Android's duration strings: "21m 36s 839ms", "1s 742ms", "7h 17m", "0ms". */
    public static long parseDuration(String s) {
        if (s == null) return 0;
        long total = 0;
        Matcher m = DURATION_UNIT.matcher(s);
        boolean any = false;
        while (m.find()) {
            any = true;
            long v = Long.parseLong(m.group(1));
            String unit = m.group(2);
            if ("d".equals(unit)) total += v * 86400000L;
            else if ("h".equals(unit)) total += v * 3600000L;
            else if ("m".equals(unit)) total += v * 60000L;
            else if ("s".equals(unit)) total += v * 1000L;
            else if ("ms".equals(unit)) total += v;
        }
        if (!any) {
            // bare number: Android prints "839ms" style, but some fields are plain counts
            return 0;
        }
        return total;
    }

    /**
     * Parses only the first duration in a string. Android mixes the measured
     * duration with unrelated counters ("3m 51s 625ms (0 times) max=260019
     * actual=260019 (running for 260019ms) realtime"), and only the first one
     * is the value we want.
     */
    public static long parseFirstDuration(String s) {
        if (s == null) return 0;
        Matcher m = DURATION_UNIT.matcher(s);
        if (!m.find()) return 0;
        long v = Long.parseLong(m.group(1));
        String unit = m.group(2);
        if ("d".equals(unit)) return v * 86400000L;
        if ("h".equals(unit)) return v * 3600000L;
        if ("m".equals(unit)) return v * 60000L;
        if ("s".equals(unit)) return v * 1000L;
        return v;
    }

    public static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (h > 0 || m > 0) sb.append(m).append("m ");
        sb.append(sec).append("s");
        return sb.toString();
    }
}
