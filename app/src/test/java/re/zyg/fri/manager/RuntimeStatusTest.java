package re.zyg.fri.manager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeStatusTest {
    @Test public void parsesExactAndChildProcesses() {
        RuntimeStatus.Snapshot snapshot = RuntimeStatus.parse(
                "PID NAME\n101 com.example.main\n202 com.example.child:worker\ninvalid row\n");

        assertTrue(snapshot.available);
        assertEquals(101, snapshot.pidFor("com.example.main"));
        assertEquals(202, snapshot.pidFor("com.example.child"));
        assertEquals(202, snapshot.pidFor("com.example.child:worker"));
        assertEquals(-1, snapshot.pidFor("com.example.missing"));
    }
}
