package re.zyg.fri.manager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of the `targets` array in /data/local/tmp/re.zyg.fri/config.json.
 *
 * Field names and types must match the external ZygiskFrida configuration protocol
 * (documented in docs/upstream_zh.md):
 *   app_name            string   (required)
 *   enabled             bool     (required)
 *   start_up_delay_ms   uint64   (required, non negative integer!)
 *   injected_libraries  [{path}] (required)
 *   child_gating        {enabled: bool, mode: string, injected_libraries: [{path}]} (optional)
 */
public final class TargetConfig {

    public static final String MODE_FREEZE = "freeze";
    public static final String MODE_KILL = "kill";
    public static final String MODE_INJECT = "inject";

    public String appName = "";
    public boolean enabled = true;
    public long startUpDelayMs = 0L;
    public final List<String> injectedLibraries = new ArrayList<>();

    public boolean childGatingEnabled = false;
    public String childGatingMode = MODE_FREEZE;
    public final List<String> childGatingLibraries = new ArrayList<>();

    /** UI only: label of the package, filled in when we know it. */
    public transient String appLabel = "";
    /** Local script-library reference; never part of the module schema. */
    public transient String scriptId = "";

    /**
     * Fields the user may not edit (imported from a sub config). Local only -
     * never written into the device config.json.
     */
    public transient final java.util.Set<String> locked = new java.util.HashSet<>();

    public static final String LOCK_APP = "app";
    public static final String LOCK_DELAY = "delay";
    public static final String LOCK_LIBRARIES = "libraries";
    public static final String LOCK_CHILD_GATING = "childGating";
    public static final String LOCK_SCRIPT = "script";

    public boolean isLocked(String what) {
        return locked.contains(what);
    }

    public void lock(String what) {
        locked.add(what);
    }

    public TargetConfig() {
    }

    public TargetConfig(String appName) {
        this.appName = appName;
    }

    public TargetConfig copy() {
        TargetConfig c = new TargetConfig(appName);
        c.enabled = enabled;
        c.startUpDelayMs = startUpDelayMs;
        c.injectedLibraries.addAll(injectedLibraries);
        c.childGatingEnabled = childGatingEnabled;
        c.childGatingMode = childGatingMode;
        c.childGatingLibraries.addAll(childGatingLibraries);
        c.appLabel = appLabel;
        c.scriptId = scriptId;
        c.locked.addAll(locked);
        return c;
    }

    public long delaySeconds() {
        return startUpDelayMs / 1000L;
    }

    public void setDelaySeconds(long seconds) {
        startUpDelayMs = Math.max(0L, seconds) * 1000L;
    }

    public boolean isProcessPattern() {
        return appName.contains(":");
    }

    // ------------------------------------------------------------------ json

    public static TargetConfig fromJson(JSONObject o) throws JSONException {
        TargetConfig t = new TargetConfig();
        t.appName = o.optString("app_name", "");
        t.enabled = o.optBoolean("enabled", true);
        t.startUpDelayMs = Math.max(0L, o.optLong("start_up_delay_ms", 0L));

        JSONArray libs = o.optJSONArray("injected_libraries");
        if (libs != null) {
            for (int i = 0; i < libs.length(); i++) {
                JSONObject lib = libs.optJSONObject(i);
                if (lib == null) {
                    continue;
                }
                String path = lib.optString("path", "");
                if (!path.isEmpty()) {
                    t.injectedLibraries.add(path);
                }
            }
        }

        JSONObject cg = o.optJSONObject("child_gating");
        if (cg != null) {
            t.childGatingEnabled = cg.optBoolean("enabled", false);
            String mode = cg.optString("mode", MODE_FREEZE);
            t.childGatingMode = normalizeMode(mode);
            JSONArray cgl = cg.optJSONArray("injected_libraries");
            if (cgl != null) {
                for (int i = 0; i < cgl.length(); i++) {
                    JSONObject lib = cgl.optJSONObject(i);
                    if (lib == null) {
                        continue;
                    }
                    String path = lib.optString("path", "");
                    if (!path.isEmpty()) {
                        t.childGatingLibraries.add(path);
                    }
                }
            }
        }
        return t;
    }

    public static String normalizeMode(String mode) {
        if (MODE_KILL.equals(mode) || MODE_INJECT.equals(mode) || MODE_FREEZE.equals(mode)) {
            return mode;
        }
        return MODE_FREEZE;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("app_name", appName);
        o.put("enabled", enabled);
        // must stay a non-negative integer: the module uses IsUint64()
        o.put("start_up_delay_ms", Math.max(0L, startUpDelayMs));

        JSONArray libs = new JSONArray();
        for (String path : injectedLibraries) {
            libs.put(new JSONObject().put("path", path));
        }
        o.put("injected_libraries", libs);

        if (childGatingEnabled) {
            JSONObject cg = new JSONObject();
            cg.put("enabled", true);
            cg.put("mode", normalizeMode(childGatingMode));
            JSONArray cgl = new JSONArray();
            for (String path : childGatingLibraries) {
                cgl.put(new JSONObject().put("path", path));
            }
            cg.put("injected_libraries", cgl);
            o.put("child_gating", cg);
        }
        return o;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("延时 ").append(delaySeconds()).append("s");
        sb.append(" · ").append(injectedLibraries.size()).append(" 个库");
        if (childGatingEnabled) {
            sb.append(" · 子进程 ").append(childGatingMode);
        }
        return sb.toString();
    }
}
