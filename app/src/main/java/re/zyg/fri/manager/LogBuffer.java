package re.zyg.fri.manager;

/** Producer-side bound keeps a noisy logcat from flooding the UI queue. */
final class LogBuffer {
    static final int MAX_CHARS = 32_000;
    private final StringBuilder pending = new StringBuilder();

    synchronized void append(String line) {
        if (line.length() >= MAX_CHARS) {
            pending.setLength(0);
            pending.append(line, line.length() - MAX_CHARS + 1, line.length());
        } else {
            int excess = pending.length() + line.length() + 1 - MAX_CHARS;
            if (excess > 0) pending.delete(0, excess);
            pending.append(line);
        }
        pending.append('\n');
    }

    synchronized String drain() {
        String result = pending.toString();
        pending.setLength(0);
        return result;
    }

    synchronized void clear() {
        pending.setLength(0);
    }
}
