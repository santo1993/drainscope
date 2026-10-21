package app.drainscope.battery;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maps Android uids to package names, labels and icons. */
public class AppResolver {

    private final PackageManager pm;
    private final Map<Integer, String> labelCache = new HashMap<Integer, String>();
    private final Map<Integer, String> pkgCache = new HashMap<Integer, String>();
    private final Map<Integer, Drawable> iconCache = new HashMap<Integer, Drawable>();

    public AppResolver(Context ctx) {
        this.pm = ctx.getPackageManager();
    }

    public String labelForUid(int uid) {
        if (uid < 0) return "unknown";
        if (labelCache.containsKey(uid)) return labelCache.get(uid);
        String label = resolveLabel(uid);
        labelCache.put(uid, label);
        return label;
    }

    private String resolveLabel(int uid) {
        String[] pkgs = packagesFor(uid);
        if (pkgs != null && pkgs.length > 0) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                CharSequence l = pm.getApplicationLabel(ai);
                if (l != null && l.length() > 0) return l.toString();
            } catch (Exception ignored) {
            }
        }
        switch (uid) {
            case 0: return "Kernel / root";
            case 1000: return "System (system_server)";
            case 1001: return "Phone";
            case 1002: return "SIM / carrier";
            case 1003: return "Wi-Fi / Bluetooth";
            case 1004: return "Input / sensors";
            case 1005: return "Graphics";
            case 1006: return "Audio";
            case 1007: return "GPS";
            case 1008: return "Media";
            case 1009: return "Camera";
            case 1010: return "Bluetooth";
            case 1011: return "Radio";
            case 2000: return "Shell";
            default:
                if (uid >= 10000 && uid < 20000) {
                    return "App uid " + (uid - 10000);
                }
                return "uid " + uid;
        }
    }

    public String packageForUid(int uid) {
        if (uid < 0) return "";
        if (pkgCache.containsKey(uid)) return pkgCache.get(uid);
        String pkg = "";
        String[] pkgs = packagesFor(uid);
        if (pkgs != null && pkgs.length > 0) pkg = pkgs[0];
        pkgCache.put(uid, pkg);
        return pkg;
    }

    private String[] packagesFor(int uid) {
        try {
            return pm.getPackagesForUid(uid);
        } catch (Throwable t) {
            return null;
        }
    }

    public Drawable iconForUid(int uid) {
        if (uid < 0) return null;
        if (iconCache.containsKey(uid)) return iconCache.get(uid);
        Drawable d = null;
        String[] pkgs = packagesFor(uid);
        if (pkgs != null && pkgs.length > 0) {
            try {
                d = pm.getApplicationIcon(pkgCache.containsKey(uid) ? pkgCache.get(uid) : pkgs[0]);
            } catch (Exception ignored) {
            }
        }
        iconCache.put(uid, d);
        return d;
    }

    /** Human readable list of the packages belonging to a uid. */
    public List<String> packageListForUid(int uid) {
        List<String> out = new ArrayList<String>();
        String[] pkgs = packagesFor(uid);
        if (pkgs != null) {
            for (String p : pkgs) out.add(p);
        }
        return out;
    }
}
