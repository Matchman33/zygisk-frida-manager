package re.zyg.fri.manager;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppControlTest {
    private static Shell.Result result(int code, String output) {
        return new Shell.Result(code, output, "", "test", false);
    }

    @Test public void stopsOwningPackageAndVerifiesWithoutRelaunching() {
        List<String> commands = new ArrayList<>();
        Shell.Result stopped = AppControl.stop("com.example.app:remote", 10, command -> {
            commands.add(command);
            return result(0, command.startsWith("ps")
                    ? "UID NAME\n10123 com.example.app\n1010124 com.example.app.other\n" : "");
        });
        assertTrue(stopped.ok());
        assertEquals(2, commands.size());
        assertEquals("am force-stop --user 10 'com.example.app'", commands.get(0));
        assertEquals("ps -A -o UID,NAME", commands.get(1));
    }

    @Test public void reportsRootFailureWithoutClaimingTheAppStopped() {
        List<String> commands = new ArrayList<>();
        Shell.Result denied = result(1, "Permission denied");
        assertSame(denied, AppControl.stop("com.example.app", 0, command -> {
            commands.add(command);
            return denied;
        }));
        assertEquals(1, commands.size());
    }

    @Test public void checksAgainUntilTheChildProcessExits() {
        int[] snapshots = {0};
        Shell.Result stopped = AppControl.stop("com.example.app", 0, command -> {
            if (!command.startsWith("ps")) return result(0, "");
            return result(0, ++snapshots[0] == 1
                    ? "UID NAME\n10123 com.example.app:remote\n" : "UID NAME\n");
        });
        assertTrue(stopped.ok());
        assertEquals(2, snapshots[0]);
    }

    @Test public void reportsAProcessThatRemainsAlive() {
        Shell.Result stopped = AppControl.stop("com.example.app", 0, command ->
                result(0, command.startsWith("ps") ? "UID NAME\n10123 com.example.app\n" : ""));
        assertFalse(stopped.ok());
        assertTrue(stopped.err().contains("com.example.app"));
    }

    @Test public void reportsFailureToVerifyProcesses() {
        Shell.Result failure = result(1, "ps failed");
        assertSame(failure, AppControl.stop("com.example.app", 0, command ->
                command.startsWith("ps") ? failure : result(0, "")));
    }
}
