package re.zyg.fri.manager;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PatchArchiveWriterTest {
    @Test public void archivePreservesInputBytesAndIncludesConfigAndLicenses() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(context.getCacheDir(), "source.so");
        File config = new File(context.getCacheDir(), "config.json");
        File output = new File(context.getCacheDir(), "patch.zip");
        byte[] data = new byte[]{0, 1, 2, 127, -1};
        Files.write(source.toPath(), data);
        Files.write(config.toPath(), SoPatchConfig.preset(0, "127.0.0.1", 27042).getBytes(StandardCharsets.UTF_8));
        Map<String, File> files = new LinkedHashMap<>();
        files.put("lib/arm64-v8a/libc++_shared.so", source);
        files.put("lib/arm64-v8a/libgadget.config.so", config);
        PatchArchiveWriter.write(context, files, output);
        assertArrayEquals(data, Files.readAllBytes(source.toPath()));
        try (ZipFile zip = new ZipFile(output)) {
            assertNotNull(zip.getEntry("lib/arm64-v8a/libc++_shared.so"));
            assertNotNull(zip.getEntry("lib/arm64-v8a/libgadget.config.so"));
            assertNotNull(zip.getEntry("licenses/Frida-COPYING.txt"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            SoPatchEngine.copy(zip.getInputStream(zip.getEntry("lib/arm64-v8a/libc++_shared.so")), bytes);
            assertArrayEquals(data, bytes.toByteArray());
        }
    }
    @Test public void invalidArchivePathIsRejected() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Map<String, File> files = new LinkedHashMap<>();
        files.put("../../unexpected", new File(context.getCacheDir(), "input"));
        File output = new File(context.getCacheDir(), "invalid.zip");
        assertThrows(IOException.class, () -> PatchArchiveWriter.write(context, files, output));
        assertFalse(output.exists());
    }
}
