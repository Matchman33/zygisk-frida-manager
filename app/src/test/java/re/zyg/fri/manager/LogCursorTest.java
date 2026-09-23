package re.zyg.fri.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class LogCursorTest {
    private static String line(String timestamp, String text) {
        return timestamp + "  123  123 I ZFM-Script: " + text;
    }

    @Test public void clearBoundarySurvivesNewCursorAndFilter() {
        for (int i = 0; i < 3; i++) {
            LogCursor.Reader reader = new LogCursor(100_000_000).reader();
            assertFalse(reader.accept(line("99.999999", "old")));
            assertFalse(reader.accept(line("100.000000", "at clear")));
            assertTrue(reader.accept(line("100.000001", "new")));
        }
    }

    @Test public void resumeSkipsHistoryButKeepsNewRecordsAtSameMicrosecond() {
        LogCursor cursor = new LogCursor(0);
        LogCursor.Reader first = cursor.reader();
        String repeated = line("100.123456", "same");
        assertTrue(first.accept(repeated));
        assertTrue(first.accept(repeated));
        LogCursor.Reader resumed = cursor.reader();
        assertFalse(resumed.accept(line("99.000000", "older")));
        assertFalse(resumed.accept(repeated));
        assertFalse(resumed.accept(repeated));
        assertTrue(resumed.accept(repeated));
        assertTrue(resumed.accept(line("100.123456", "different")));
        assertTrue(resumed.accept(line("101.000000", "later")));
        assertEquals("101.000000", cursor.since());
    }

    @Test public void restoredCursorSkipsAlreadyRenderedBoundary() {
        LogCursor cursor = new LogCursor(5);
        String line = line("100.000001", "seen");
        assertTrue(cursor.reader().accept(line));
        LogCursor restored = new LogCursor(5);
        restored.restore(cursor.latest(), cursor.boundaryLines());
        assertFalse(restored.reader().accept(line));
    }

    @Test public void parseUsesMicrosecondsAndRejectsNonRecords() {
        assertEquals(1234567890123456L, LogCursor.timestamp(line("1234567890.123456", "ok")));
        assertEquals(100100000L, LogCursor.timestamp(line("100.1", "ok")));
        assertEquals(-1, LogCursor.timestamp("--------- beginning of main"));
        assertEquals(-1, LogCursor.timestamp("su: permission denied"));
    }
}
