package re.zyg.fri.manager;

import org.junit.Test;
import static org.junit.Assert.*;

public class LogBufferTest {
    @Test public void floodIsBoundedAndRetainsLatestLines() {
        LogBuffer buffer = new LogBuffer();
        for (int i = 0; i < 100_000; i++) buffer.append("logcat message " + i);
        String batch = buffer.drain();
        assertTrue(batch.length() <= LogBuffer.MAX_CHARS);
        assertTrue(batch.endsWith("logcat message 99999\n"));
        assertEquals("", buffer.drain());
    }

    @Test public void oversizedLineAndClearAreBounded() {
        LogBuffer buffer = new LogBuffer();
        buffer.append(new String(new char[100_000]).replace('\0', 'x'));
        assertEquals(LogBuffer.MAX_CHARS, buffer.drain().length());
        buffer.append("old capture");
        buffer.clear();
        buffer.append("new capture");
        assertEquals("new capture\n", buffer.drain());
    }

    @Test public void sessionsNeverMix() {
        LogBuffer old = new LogBuffer();
        LogBuffer current = new LogBuffer();
        old.append("old");
        current.append("current");
        assertEquals("current\n", current.drain());
    }
}
