package re.zyg.fri.manager;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A read-only snapshot of target processes currently visible to root. */
final class RuntimeStatus {
    static final class Snapshot {
        final boolean available;
        final Map<String, Integer> pids;

        Snapshot(boolean available, Map<String, Integer> pids) {
            this.available = available;
            this.pids = Collections.unmodifiableMap(new HashMap<>(pids));
        }

        int pidFor(String processName) {
            Integer exact = pids.get(processName);
            if (exact != null) return exact;
            if (processName.contains(":")) return -1;
            for (Map.Entry<String, Integer> entry : pids.entrySet()) {
                if (entry.getKey().startsWith(processName + ":")) return entry.getValue();
            }
            return -1;
        }
    }

    private RuntimeStatus() {
    }

    static Snapshot inspect(Context context, List<TargetConfig> targets) {
        if (targets.isEmpty()) return new Snapshot(true, Collections.emptyMap());
        Shell.Result result = Shell.su(context, "ps -A -o PID,NAME");
        if (!result.ok()) return new Snapshot(false, Collections.emptyMap());
        return parse(result.stdout);
    }

    static Snapshot parse(String output) {
        Map<String, Integer> pids = new HashMap<>();
        if (output == null) return new Snapshot(false, pids);
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.toUpperCase(java.util.Locale.ROOT).startsWith("PID")) continue;
            String[] columns = line.split("\\s+", 2);
            if (columns.length != 2) continue;
            try {
                int pid = Integer.parseInt(columns[0]);
                if (pid > 0 && !columns[1].trim().isEmpty()) pids.put(columns[1].trim(), pid);
            } catch (NumberFormatException ignored) {
            }
        }
        return new Snapshot(true, pids);
    }
}
