package re.zyg.fri.manager;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class DeviceConfigTransactionTest {
    @Test public void commitBacksUpEveryFileAndInstallsRollbackTrap() {
        String command = DeviceConfigTransaction.buildCommitCommand("/module/.txn", Arrays.asList(
                new DeviceConfigTransaction.Entry("/module/.txn/files/0", "/module/config.json", "0644"),
                new DeviceConfigTransaction.Entry("/module/.txn/files/1", "/module/lib.config.so", "0644")));

        assertTrue(command.contains("trap 'rollback' 0 1 2 15"));
        assertTrue(command.contains("cp -pf '/module/config.json' '/module/.txn/backup/0'"));
        assertTrue(command.contains("cp -pf '/module/lib.config.so' '/module/.txn/backup/1'"));
        assertTrue(command.contains("mv -f '/module/.txn/files/0' '/module/config.json'"));
        assertTrue(command.contains("rm -f '/module/lib.config.so'"));
        assertTrue(command.contains("echo __ZFM_COMMIT_OK__"));
    }
}
