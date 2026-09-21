package re.zyg.fri.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Compares the locally desired configuration with the last successful push. */
final class DeploymentState {
    enum Status { NEVER_PUSHED, CURRENT, DIRTY }

    private static final String PREFS = "deployment_state";
    private static final String KEY_FINGERPRINT = "applied_fingerprint";
    private static final String KEY_TIME = "applied_time";

    private DeploymentState() {
    }

    static Status status(Context context) {
        String applied = prefs(context).getString(KEY_FINGERPRINT, "");
        if (applied == null || applied.isEmpty()) return Status.NEVER_PUSHED;
        return applied.equals(fingerprint(context)) ? Status.CURRENT : Status.DIRTY;
    }

    static void markApplied(Context context) {
        markApplied(context, fingerprint(context));
    }

    static void markApplied(Context context, String fingerprint) {
        prefs(context).edit()
                .putString(KEY_FINGERPRINT, fingerprint)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .commit();
    }

    static long appliedTime(Context context) {
        return prefs(context).getLong(KEY_TIME, 0L);
    }

    static String fingerprint(Context context) {
        Prefs global = Prefs.get(context);
        StringBuilder source = new StringBuilder();
        append(source, global.moduleDir());
        append(source, global.gadgetPath());
        append(source, global.gadget32Path());
        append(source, global.childGadgetPath());
        append(source, global.gadgetConfigEnabled());
        append(source, global.gadgetConfigPath());
        append(source, global.gadgetConfigJson());
        append(source, global.scriptPath());
        append(source, global.scriptCipherPath());
        append(source, global.scriptMode());
        append(source, global.scriptKey());

        List<TargetConfig> targets = new ArrayList<>(ConfigStore.get(context).targets());
        Collections.sort(targets, new Comparator<TargetConfig>() {
            @Override
            public int compare(TargetConfig left, TargetConfig right) {
                return left.appName.compareTo(right.appName);
            }
        });
        for (TargetConfig target : targets) {
            try {
                append(source, target.toJson().toString());
            } catch (Exception e) {
                append(source, target.appName);
            }
            append(source, target.scriptId);
            if (!target.scriptId.isEmpty()) {
                Prefs profile = Prefs.forScript(context, target.scriptId);
                append(source, profile.gadgetConfigEnabled());
                append(source, profile.gadgetConfigJson());
                append(source, profile.scriptPath());
                append(source, profile.scriptCipherPath());
                append(source, profile.scriptMode());
                append(source, profile.scriptKey());
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : digest) {
                out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void append(StringBuilder out, Object value) {
        String text = String.valueOf(value);
        out.append(text.length()).append(':').append(text).append(';');
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
