package re.zyg.fri.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists installed applications and parses standalone APK files. */
public final class AppRepository {

    private static List<AppEntry> cache;
    private static Map<String, String> labelCache;

    private AppRepository() {
    }

    public static void invalidate() {
        cache = null;
        labelCache = null;
    }

    public static synchronized List<AppEntry> all(Context ctx) {
        if (cache != null) {
            return cache;
        }
        PackageManager pm = ctx.getPackageManager();
        List<AppEntry> result = new ArrayList<>();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            return result;
        }
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) {
                continue;
            }
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Throwable t) {
                label = ai.packageName;
            }
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(ai);
            } catch (Throwable ignored) {
            }
            boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            result.add(new AppEntry(label, ai.packageName, ai.sourceDir, system, icon));
        }
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int c = a.label.compareToIgnoreCase(b.label);
                return c != 0 ? c : a.packageName.compareToIgnoreCase(b.packageName);
            }
        });
        cache = result;
        return result;
    }

    public static List<AppEntry> matching(Context ctx, String query, boolean includeSystem) {
        List<AppEntry> all = all(ctx);
        List<AppEntry> out = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (AppEntry e : all) {
            if (!includeSystem && e.system) {
                continue;
            }
            if (needle.isEmpty()
                    || e.label.toLowerCase(Locale.ROOT).contains(needle)
                    || e.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(e);
            }
        }
        return out;
    }

    public static AppEntry find(Context ctx, String packageName) {
        if (packageName == null) {
            return null;
        }
        for (AppEntry e : all(ctx)) {
            if (packageName.equals(e.packageName)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Package name -> app label, without touching any icon.
     *
     * Building the full {@link AppEntry} list decodes every icon, which is far
     * too slow for a plain "which app is this" lookup from a list row.
     */
    private static synchronized Map<String, String> labels(Context ctx) {
        if (labelCache != null) {
            return labelCache;
        }
        Map<String, String> map = new HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai == null || ai.packageName == null) {
                    continue;
                }
                String label;
                try {
                    label = String.valueOf(pm.getApplicationLabel(ai));
                } catch (Throwable t) {
                    label = ai.packageName;
                }
                map.put(ai.packageName, label);
            }
        } catch (Throwable ignored) {
        }
        labelCache = map;
        return labelCache;
    }

    public static String labelOf(Context ctx, String packageName) {
        if (packageName == null) {
            return "";
        }
        String label = labels(ctx).get(packageName);
        return label == null ? packageName : label;
    }

    public static Drawable iconOf(Context ctx, String packageName) {
        if (packageName == null || packageName.contains(":")) {
            return null;
        }
        try {
            return ctx.getPackageManager().getApplicationIcon(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Builds the label lookup off the UI thread. */
    public static void warmLabels(final Context ctx) {
        Bg.run(new Runnable() {
            @Override
            public void run() {
                labels(ctx.getApplicationContext());
            }
        });
    }

    /** Parses an APK file that is not installed (yet). */
    public static AppEntry fromApkFile(Context ctx, String path) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(path, 0);
        if (info == null || info.applicationInfo == null) {
            return null;
        }
        ApplicationInfo ai = info.applicationInfo;
        // required for label/icon loading from an archive
        ai.sourceDir = path;
        ai.publicSourceDir = path;
        String label;
        try {
            label = String.valueOf(pm.getApplicationLabel(ai));
        } catch (Throwable t) {
            label = info.packageName;
        }
        Drawable icon = null;
        try {
            icon = pm.getApplicationIcon(ai);
        } catch (Throwable ignored) {
        }
        return new AppEntry(label, info.packageName, path, false, icon);
    }
}
