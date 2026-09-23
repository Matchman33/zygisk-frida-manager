package re.zyg.fri.manager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** LIEF owns ELF parsing, relocation and rewriting; Java only validates input and paths. */
public final class ElfPatcher {
    public static final long MAX_SO_BYTES = 256L * 1024 * 1024;
    private static boolean loaded;

    private ElfPatcher() {}

    private static synchronized void load() throws IOException {
        if (loaded) return;
        try { System.loadLibrary("zfm_elf"); loaded = true; }
        catch (UnsatisfiedLinkError error) { throw new IOException("当前设备无法加载 ELF 补丁引擎", error); }
    }

    public static final class Info {
        public final String abi;
        public final List<String> needed;
        Info(String[] values) throws IOException {
            if (values == null || values.length < 1) throw new IOException("无法读取 ELF 信息");
            abi = values[0];
            needed = Collections.unmodifiableList(Arrays.asList(Arrays.copyOfRange(values, 1, values.length)));
        }
    }

    public static synchronized Info inspect(File file) throws IOException {
        if (!file.isFile() || file.length() < 52 || file.length() > MAX_SO_BYTES)
            throw new IOException("请选择有效的 SO 文件，最大支持 256 MiB");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] header = new byte[4];
            if (in.read(header) != 4 || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F')
                throw new IOException("所选文件不是 ELF 格式的 SO");
        }
        load();
        return new Info(inspectNative(file.getAbsolutePath()));
    }

    public static synchronized Info patch(File input, File output, String dependency) throws IOException {
        if (input.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("不能覆盖原 SO 文件");
        inspect(input);
        patchNative(input.getAbsolutePath(), output.getAbsolutePath(), dependency);
        return inspect(output);
    }

    private static native String[] inspectNative(String input) throws IOException;
    private static native void patchNative(String input, String output, String dependency) throws IOException;
}
