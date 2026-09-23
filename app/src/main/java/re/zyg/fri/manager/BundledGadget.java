package re.zyg.fri.manager;

import android.content.Context;
import org.json.JSONObject;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class BundledGadget {
    private final Context context;
    private final JSONObject manifest;

    public BundledGadget(Context context) throws Exception {
        this.context = context.getApplicationContext();
        try (InputStream input = context.getAssets().open("gadget/manifest.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            manifest = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public String version() throws Exception { return manifest.getString("version"); }

    public void extract(String abi, File output) throws Exception {
        JSONObject entry = manifest.getJSONObject("libraries").optJSONObject(abi);
        if (entry == null) throw new IOException("没有匹配 " + abi + " 的内置 Gadget");
        long expectedSize = entry.getLong("size");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream asset = context.getAssets().open("gadget/" + entry.getString("asset"));
             InputStream input = new XZInputStream(asset, 128 * 1024);
             OutputStream destination = new FileOutputStream(output)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) {
                size += n;
                if (size > expectedSize) throw new IOException("内置 Gadget 大小校验失败");
                destination.write(buffer, 0, n);
                digest.update(buffer, 0, n);
            }
        }
        if (size != expectedSize || !hex(digest.digest()).equals(entry.getString("sha256"))) {
            output.delete();
            throw new IOException("内置 Gadget SHA-256 校验失败");
        }
        try (RandomAccessFile elf = new RandomAccessFile(output, "r")) {
            byte[] header = new byte[20];
            elf.readFully(header);
            int machine = (header[18] & 255) | ((header[19] & 255) << 8);
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                    || header[4] != entry.getInt("elfClass") || header[5] != 1 || machine != entry.getInt("machine"))
                throw new IOException("内置 Gadget 架构校验失败");
        }
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
