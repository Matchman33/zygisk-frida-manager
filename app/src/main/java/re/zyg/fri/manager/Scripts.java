package re.zyg.fri.manager;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Everything that happens after the user picked a script file.
 *
 * Depending on the settings the manager either pushes the script as-is, pushes
 * the ciphertext plus a small generated loader, or decrypts locally first:
 *
 * <pre>
 *   plain              -> scripts/x.js                       (gadget loads it directly)
 *   cipher, on device  -> scripts/x.so + scripts/x.loader.js  (loader decrypts and loads)
 *   cipher, local      -> scripts/x.js                        (decrypted by the manager)
 * </pre>
 *
 * The loader is plain generated JavaScript, so the user's own script never has
 * to contain any manager specific code.
 */
public final class Scripts {

    public interface Callback {
        void onDone(String devicePath, String error, String note);
    }

    private Scripts() {
    }

    // ------------------------------------------------------------------ names

    public static String displayName(Context ctx, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (name == null || name.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            name = last == null ? "script.js" : new File(last).getName();
        }
        return sanitize(name);
    }

    public static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        String s = sb.toString();
        return s.isEmpty() ? "script.js" : s;
    }

    /** {@code mh_index.so -> mh_index}, {@code a.b.js -> a.b} */
    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    // ------------------------------------------------------------------ files

    public static File copyToCache(Context ctx, Uri uri, String name) throws IOException {
        File dir = new File(ctx.getCacheDir(), "import");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录 " + dir);
        }
        File out = new File(dir, java.util.UUID.randomUUID() + "-" + name);
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取所选文件");
        }
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                fos.write(buf, 0, r);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            fos.close();
        }
        return out;
    }

    public static String readLocal(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return new String(bos.toByteArray(), Shell.UTF8);
        } finally {
            in.close();
        }
    }

    private static void writeLocal(File file, String text) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(text.getBytes(Shell.UTF8));
        } finally {
            fos.close();
        }
    }

    // ------------------------------------------------------------------ plan

    /** One line description of what importing a script will do right now. */
    public static String describePlan(Prefs p) {
        return describePlan(p.scriptMode(), p.scriptName());
    }

    static String describePlan(String mode, String scriptName) {
        String name = scriptName;
        if (name.isEmpty()) {
            name = "script";
        }
        String base = baseName(name);
        if (Prefs.MODE_PLAIN.equals(mode)) {
            return "明文直推 → " + name;
        }
        if (Prefs.MODE_DECRYPT_LOCAL.equals(mode)) {
            return "密文 → 管理器解密 → 明文 " + base + ".js";
        }
        if (Prefs.MODE_DECRYPT_DEVICE.equals(mode)) {
            return "密文 → 设备内解密 → " + base + ".so + " + base + ".loader.js";
        }
        return "明文 → 管理器加密 → " + base + ".so + " + base + ".loader.js"
                + "（设备上只有密文）";
    }

    /**
     * Renders a loader for an already pushed ciphertext - used when a sub config
     * is imported, so the receiving manager can regenerate it locally.
     */
    public static String buildLoaderFor(Prefs p, String scriptName, String cipherPath) {
        ScriptCrypto.Bootstrap b = newBootstrap(p, scriptName);
        b.encrypted = true;
        b.source = cipherPath;
        return b.render();
    }

    private static ScriptCrypto.Bootstrap newBootstrap(Prefs p, String scriptName) {
        ScriptCrypto.Bootstrap b = new ScriptCrypto.Bootstrap();
        b.key = p.scriptKey();
        b.name = scriptName;
        b.msgStart = "ZygiskFrida 已注入 · 正在加载脚本";
        b.msgReady = "脚本已加载: " + scriptName;
        b.msgFail = "脚本加载失败: ";
        return b;
    }

    // ------------------------------------------------------------------ import

    /**
     * Copies the picked file into {@code <moduleDir>/scripts/} as root, generates
     * a loader when needed and (optionally) points the gadget config at it.
     */
    public static void importScript(final Context ctx, final Uri uri, final boolean wireGadget,
                                    final Callback cb) {
        importScript(ctx, uri, Prefs.get(ctx), wireGadget, cb);
    }

    public static void importScript(final Context ctx, final Uri uri, final Prefs p,
                                    final boolean wireGadget, final Callback cb) {
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final String picked = displayName(ctx, uri);
                String error = null;
                String note = null;
                String gadgetPath = null;
                String cipherPath = "";

                try {
                    File local = copyToCache(ctx, uri, picked);
                    if (!Shell.ensureDir(ctx, p.scriptsDir())) {
                        error = "无法创建脚本目录 " + p.scriptsDir() + "（root 权限？模块目录？）";
                    } else if (Prefs.MODE_PLAIN.equals(p.scriptMode())) {
                        Outcome o = importPlain(ctx, p, local, picked);
                        error = o.error;
                        gadgetPath = o.path;
                        note = o.note;
                        cipherPath = o.cipherPath;
                    } else if (Prefs.MODE_ENCRYPT_DEVICE.equals(p.scriptMode())) {
                        Outcome o = importAndEncrypt(ctx, p, local, picked);
                        error = o.error;
                        gadgetPath = o.path;
                        note = o.note;
                        cipherPath = o.cipherPath;
                    } else {
                        Outcome o = importEncrypted(ctx, p, local, picked);
                        error = o.error;
                        gadgetPath = o.path;
                        note = o.note;
                        cipherPath = o.cipherPath;
                    }
                    local.delete();
                } catch (IOException e) {
                    error = e.toString();
                }

                if (error == null && wireGadget && gadgetPath != null) {
                    p.setScriptPath(gadgetPath);
                    p.setScriptName(picked);
                    p.setScriptCipherPath(cipherPath);
                    p.setGadgetConfigJson(wireGadgetToScript(p.gadgetConfigJson(), gadgetPath));
                    p.setGadgetConfigEnabled(true);
                    // push it right away: "pick a script" should be the whole job
                    Shell.Result cfg = p.profileId().isEmpty() ? Pusher.writeGadgetConfig(ctx)
                            : new Shell.Result(0, "已保存独立脚本配置", "", "", false);
                    if (!cfg.ok()) {
                        error = "脚本已推送，但 gadget 配置写入设备失败:\n" + cfg.dump()
                                + "\n可以回列表点「推送并应用」重试。";
                    }
                }

                final String err = error;
                final String devicePath = gadgetPath == null ? "" : gadgetPath;
                final String finalNote = note == null ? "" : note;
                if (cb != null) {
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            cb.onDone(devicePath, err, finalNote);
                        }
                    });
                }
            }
        });
    }

    private static final class Outcome {
        String path = "";
        String note = "";
        /** device path of the encrypted payload, "" when the script is plain */
        String cipherPath = "";
        String error;

        static Outcome ok(String path, String note) {
            Outcome o = new Outcome();
            o.path = path;
            o.note = note == null ? "" : note;
            return o;
        }

        static Outcome fail(String error) {
            Outcome o = new Outcome();
            o.error = error;
            return o;
        }

        Outcome withCipher(String devicePath) {
            this.cipherPath = devicePath == null ? "" : devicePath;
            return this;
        }
    }

    /** @return the device path the gadget should load, or an error */
    private static Outcome importEncrypted(Context ctx, Prefs p, File local, String picked) {
        String text;
        try {
            text = readLocal(local);
        } catch (IOException e) {
            return Outcome.fail("读取所选文件失败: " + e);
        }
        if (!ScriptCrypto.keyIsAscii(p.scriptKey())) {
            return Outcome.fail("密钥必须是 ASCII 字符（XOR 用的是 charCodeAt）");
        }
        // decrypt here first: a wrong key or a wrong format is a setup mistake we
        // can report right now instead of leaving the user with a silent device
        final String plain = ScriptCrypto.decrypt(text, p.scriptKey());
        if (plain == null) {
            return Outcome.fail("解密失败：确认这个文件是 XOR+Base64（cry.js 产出的 index.so 那种），"
                    + "且密钥正确。\n如果它其实是明文脚本，"
                    + "请在「Gadget / 脚本配置」里关掉「脚本已加密」。");
        }

        String base = baseName(picked);
        if (Prefs.MODE_DECRYPT_DEVICE.equals(p.scriptMode())) {
            String encDevice = p.scriptsDir() + "/" + picked;
            Shell.Result pushed = Shell.pushFile(ctx, local, encDevice, "0644");
            if (!pushed.ok()) {
                return Outcome.fail("密文推送失败:\n" + pushed.dump());
            }
            String loaderDevice = p.scriptsDir() + "/" + base + ".loader.js";
            ScriptCrypto.Bootstrap b = newBootstrap(p, base + ".js");
            b.encrypted = true;
            b.source = encDevice;
            Shell.Result loader = writeLoader(ctx, loaderDevice, b.render());
            if (!loader.ok()) {
                return Outcome.fail("loader 写入失败:\n" + loader.dump());
            }
            return Outcome.ok(loaderDevice,
                    "密文留在设备上（" + encDevice + "），由 loader 在目标进程内解密")
                    .withCipher(encDevice);
        }

        // Local decryption writes a plain script that the gadget loads directly.
        File tmp;
        try {
            tmp = File.createTempFile("script-", ".js", ctx.getCacheDir());
            writeLocal(tmp, plain);
        } catch (IOException e) {
            return Outcome.fail("写临时明文失败: " + e);
        }
        String plainDevice = p.scriptsDir() + "/" + base + ".js";
        Shell.Result pushed = Shell.pushFile(ctx, tmp, plainDevice, "0644");
        tmp.delete();
        if (!pushed.ok()) {
            return Outcome.fail("明文脚本推送失败:\n" + pushed.dump());
        }
        return Outcome.ok(plainDevice, "管理器已解密，设备上是明文（" + plainDevice + "）");
    }

    /**
     * Plain input, encrypted by the manager before it leaves the phone.
     *
     * The device only ever sees the ciphertext, which is the whole point: no
     * plaintext payload lying around in /data/local/tmp for a scanner to find.
     */
    private static Outcome importAndEncrypt(Context ctx, Prefs p, File local, String picked) {
        String text;
        try {
            text = readLocal(local);
        } catch (IOException e) {
            return Outcome.fail("读取所选文件失败: " + e);
        }
        String key = p.scriptKey();
        if (!ScriptCrypto.keyIsAscii(key)) {
            return Outcome.fail("密钥必须是 ASCII 字符（XOR 用的是 charCodeAt）");
        }
        if (ScriptCrypto.looksEncrypted(text)) {
            return Outcome.fail("这个文件看起来已经是密文（单行 Base64）。\n"
                    + "· 明文脚本 → 保持「明文 → 管理器加密」\n"
                    + "· 已经是密文 → 把脚本处理方式改成「密文 → 设备内解密」");
        }
        String cipher = ScriptCrypto.encrypt(text, key);
        // never push something we cannot read back
        if (!text.equals(ScriptCrypto.decrypt(cipher, key))) {
            return Outcome.fail("加密自检失败（解回来的内容不一致），已中止推送");
        }

        String base = baseName(picked);
        File tmp;
        try {
            tmp = File.createTempFile("script-", ".so", ctx.getCacheDir());
            writeLocal(tmp, cipher);
        } catch (IOException e) {
            return Outcome.fail("写临时密文失败: " + e);
        }
        String encDevice = p.scriptsDir() + "/" + base + ".so";
        Shell.Result pushed = Shell.pushFile(ctx, tmp, encDevice, "0644");
        tmp.delete();
        if (!pushed.ok()) {
            return Outcome.fail("密文推送失败:\n" + pushed.dump());
        }

        String loaderDevice = p.scriptsDir() + "/" + base + ".loader.js";
        ScriptCrypto.Bootstrap b = newBootstrap(p, base + ".js");
        b.encrypted = true;
        b.source = encDevice;
        Shell.Result loader = writeLoader(ctx, loaderDevice, b.render());
        if (!loader.ok()) {
            return Outcome.fail("loader 写入失败:\n" + loader.dump());
        }
        return Outcome.ok(loaderDevice,
                "已加密（明文 " + text.length() + " 字符 → 密文 " + cipher.length()
                        + " 字符），设备上只有密文 " + encDevice).withCipher(encDevice);
    }

    private static Outcome importPlain(Context ctx, Prefs p, File local, String picked) {
        String scriptDevice = p.scriptsDir() + "/" + picked;
        Shell.Result pushed = Shell.pushFile(ctx, local, scriptDevice, "0644");
        if (!pushed.ok()) {
            return Outcome.fail("脚本推送失败:\n" + pushed.dump());
        }

        String note = "";
        try {
            if (ScriptCrypto.looksEncrypted(readLocal(local))) {
                note = "提示：这个文件看起来是纯 Base64（可能是加密脚本）。"
                        + "如果脚本没生效，请在「Gadget / 脚本配置」里打开「脚本已加密」。";
            }
        } catch (IOException ignored) {
        }

        return Outcome.ok(scriptDevice, note);
    }

    private static Shell.Result writeLoader(Context ctx, String devicePath, String js) {
        Shell.Result r = Shell.writeTextFile(ctx, devicePath, js);
        if (r.ok()) {
            Shell.chmod(ctx, devicePath, "0644");
            Shell.chownRoot(ctx, devicePath);
        }
        return r;
    }

    // ------------------------------------------------------------------ gadget

    /**
     * Rewrites a gadget config so that it auto-loads {@code devicePath}.
     *
     * `on_load` is removed on purpose: with `interaction.type = "script"` there is
     * no client around to resume a paused process, so leaving `wait` in place
     * would freeze the target app.
     */
    public static String wireGadgetToScript(String currentJson, String devicePath) {
        JSONObject root;
        try {
            root = new JSONObject(currentJson == null || currentJson.trim().isEmpty()
                    ? "{}" : currentJson);
        } catch (Exception e) {
            root = new JSONObject();
        }
        JSONObject interaction = root.optJSONObject("interaction");
        if (interaction == null) {
            interaction = new JSONObject();
            try {
                root.put("interaction", interaction);
            } catch (Exception ignored) {
            }
        }
        try {
            interaction.put("type", GadgetHelper.MODE_SCRIPT);
            interaction.put("path", devicePath);
            interaction.remove("on_load");
            // listen / connect only keys: meaningless here and just noise in the file
            interaction.remove("address");
            interaction.remove("port");
            interaction.remove("on_port_conflict");
            if (!interaction.has("on_change")) {
                interaction.put("on_change", "reload");
            }
            return GadgetHelper.prettify(root) + "\n";
        } catch (Exception e) {
            return currentJson;
        }
    }
}
