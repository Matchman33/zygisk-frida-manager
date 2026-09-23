package re.zyg.fri.manager;

import android.content.Context;
import java.io.*;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The exported archive contains only explicit files; no original URI is opened for writing. */
final class PatchArchiveWriter {
    private PatchArchiveWriter() {}

    static void write(Context context, Map<String, File> files, File archive) throws IOException {
        for (String name : files.keySet()) {
            if (name.startsWith("/") || name.contains("\\") || name.contains("../") || name.indexOf('\0') >= 0)
                throw new IOException("补丁包包含无效文件路径");
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
            zip.setLevel(1);
            for (Map.Entry<String, File> item : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                try (InputStream in = new FileInputStream(item.getValue())) { SoPatchEngine.copy(in, zip); }
                zip.closeEntry();
            }
            String[] licenses = context.getAssets().list("licenses");
            if (licenses == null) throw new IOException("缺少第三方组件许可文件");
            Arrays.sort(licenses);
            for (String license : licenses) {
                zip.putNextEntry(new ZipEntry("licenses/" + license));
                try (InputStream in = context.getAssets().open("licenses/" + license)) { SoPatchEngine.copy(in, zip); }
                zip.closeEntry();
            }
        } catch (IOException error) {
            archive.delete();
            throw error;
        }
    }
}
