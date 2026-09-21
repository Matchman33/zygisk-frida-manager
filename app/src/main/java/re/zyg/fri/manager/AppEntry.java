package re.zyg.fri.manager;

import android.graphics.drawable.Drawable;

/** One installed application (or one APK file on disk). */
public final class AppEntry {

    public final String label;
    public final String packageName;
    public final String apkPath;
    public final boolean system;
    public final Drawable icon;

    public AppEntry(String label, String packageName, String apkPath, boolean system,
                    Drawable icon) {
        this.label = label == null ? "" : label;
        this.packageName = packageName == null ? "" : packageName;
        this.apkPath = apkPath == null ? "" : apkPath;
        this.system = system;
        this.icon = icon;
    }

    @Override
    public String toString() {
        return label + " (" + packageName + ")";
    }
}
