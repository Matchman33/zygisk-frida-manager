package re.zyg.fri.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Timestamp boundary for a log view. All mutation happens on the UI thread. */
public final class LogCursor {
    private final long clearedThrough;
    private long latest;
    private final Map<String, Integer> latestLines = new HashMap<>();

    public LogCursor(long clearedThrough) {
        this.clearedThrough = clearedThrough;
        latest = clearedThrough;
    }

    public long latest() { return latest; }

    public String since() {
        return String.format(Locale.ROOT, "%d.%06d", latest / 1_000_000, latest % 1_000_000);
    }

    public List<String> boundaryLines() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : latestLines.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) lines.add(entry.getKey());
        }
        return lines;
    }

    public void restore(long timestamp, List<String> lines) {
        if (timestamp <= clearedThrough || lines == null) return;
        latest = timestamp;
        latestLines.clear();
        for (String line : lines) latestLines.put(line, count(latestLines, line) + 1);
    }

    public Reader reader() { return new Reader(); }

    public final class Reader {
        private final long resumedAt = latest;
        private final Map<String, Integer> replay = new HashMap<>(latestLines);

        public boolean accept(String line) {
            return accept(line, timestamp(line));
        }

        boolean accept(String line, long timestamp) {
            if (timestamp <= clearedThrough || timestamp < resumedAt) return false;
            if (timestamp == resumedAt) {
                int count = count(replay, line);
                if (count > 0) {
                    replay.put(line, count - 1);
                    return false;
                }
            }
            if (timestamp > latest) {
                latest = timestamp;
                latestLines.clear();
            }
            if (timestamp == latest) latestLines.put(line, count(latestLines, line) + 1);
            return true;
        }
    }

    private static int count(Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        return value == null ? 0 : value;
    }

    public static long timestamp(String line) {
        String text = line.trim();
        int end = text.indexOf(' ');
        if (end < 0) return -1;
        String token = text.substring(0, end);
        int dot = token.indexOf('.');
        if (dot <= 0) return -1;
        try {
            String fraction = token.substring(dot + 1);
            if (fraction.isEmpty() || fraction.length() > 9) return -1;
            for (int i = 0; i < fraction.length(); i++) {
                char digit = fraction.charAt(i);
                if (digit < '0' || digit > '9') return -1;
            }
            String micros = (fraction + "000000").substring(0, 6);
            long seconds = Long.parseLong(token.substring(0, dot));
            if (seconds < 0 || seconds >= Long.MAX_VALUE / 1_000_000L) return -1;
            return seconds * 1_000_000L + Long.parseLong(micros);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }
}
