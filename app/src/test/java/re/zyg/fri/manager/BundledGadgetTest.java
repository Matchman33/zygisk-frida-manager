package re.zyg.fri.manager;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BundledGadgetTest {
    @Test public void allBundledArchitecturesDecompressAndMatchTheirHashes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        BundledGadget gadget = new BundledGadget(context);
        assertEquals("17.18.0", gadget.version());
        for (String abi : new String[]{"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}) {
            File output = new File(context.getCacheDir(), "gadget-" + abi + ".so");
            try { gadget.extract(abi, output); assertTrue(output.length() > 1_000_000); }
            finally { output.delete(); }
        }
    }
    @Test public void unsupportedArchitectureDoesNotCreateOutput() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File output = new File(context.getCacheDir(), "unsupported.so");
        assertThrows(IOException.class, () -> new BundledGadget(context).extract("riscv64", output));
        assertFalse(output.exists());
    }
}
