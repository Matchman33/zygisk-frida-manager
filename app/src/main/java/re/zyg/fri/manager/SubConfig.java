package re.zyg.fri.manager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Per application "sub config" - the thing you hand to somebody else.
 *
 * Design goals, in order:
 *
 * <ol>
 *   <li>one file per app, self contained: it carries the target settings, the
 *       gadget config, the encrypted payload and the (sealed) script key, so the
 *       recipient does not need anything else;</li>
 *   <li>most fields are locked: the importer refuses to touch them and the UI
 *       shows them read only. Only what the exporter allowed stays editable;</li>
 *   <li>the key is never shown: it is sealed with AES-GCM inside the file, the
 *       importing manager keeps it hidden (never rendered, never editable);</li>
 *   <li>tamper evident: an HMAC over every field catches hand edits of locked
 *       values (a plain hash would not - the key material is part of the MAC).</li>
 * </ol>
 *
 * Honest limitation, also stated in the README: the recipient's device must be
 * able to decrypt the payload at runtime, so the key has to exist there in some
 * usable form. This file level sealing plus the loader level obfuscation stops
 * casual copying, it is not cryptographic secrecy against a determined reverse
 * engineer.
 *
 * The format is a flat, line oriented text file (not JSON) on purpose: it needs
 * no JSON library, stays readable/editable in a text editor, and the whole class
 * can therefore be tested on a plain JVM.
 */
public final class SubConfig {

    public static final String MAGIC = "zfm-subconfig";
    public static final int VERSION = 1;
    public static final String FILE_SUFFIX = ".zfmcfg";

    /** Obfuscation secret baked into the app. Not a real secret - see class docs. */
    private static final String APP_SECRET = "zfm:subconfig:v1:2f9a41c7d5b3";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Same values as {@code Prefs.MODE_*}; duplicated so this class stays Android free. */
    private static final String MODE_PLAIN = "plain";

    // editability flags
    public static final String EDIT_ENABLED = "enabled";
    public static final String EDIT_DELAY = "delay";

    // always locked (for display in the UI and docs)
    public static final String[] ALWAYS_LOCKED = {
            "目标应用（包名）", "注入的库", "脚本与加密方式", "Gadget 配置", "密钥",
    };

    private SubConfig() {
    }

    public static final class SubConfigException extends Exception {
        public SubConfigException(String message) {
            super(message);
        }
    }

    /** Result of parsing a sub config file. */
    public static final class Imported {
        public String appName = "";
        public String appLabel = "";
        public long startUpDelayMs;
        public boolean enabled = true;
        public final List<String> libs = new ArrayList<>();
        public String childGatingJson = "";
        public String scriptMode = MODE_PLAIN;
        public String scriptName = "";
        public String cipherName = "";
        public String cipherSha256 = "";
        /** the payload as it has to be written on the device: single line base64 */
        public String cipherBase64 = "";
        public String gadgetJson = "";
        public String key = "";
        public final Set<String> editable = new TreeSet<>();

        public boolean canEdit(String what) {
            return editable.contains(what);
        }

        public String editableText() {
            if (editable.isEmpty()) {
                return "无（全部锁定）";
            }
            List<String> out = new ArrayList<>();
            for (String e : editable) {
                out.add(EDIT_DELAY.equals(e) ? "延时" : (EDIT_ENABLED.equals(e) ? "启用状态" : e));
            }
            return join(out, "、");
        }
    }

    // ------------------------------------------------------------------ export

    /**
     * Builds the file content. {@code cipherBytes} may be null when the script
     * mode carries no ciphertext (then the recipient has to supply the payload
     * themselves).
     */
    public static String export(String appName, String appLabel,
                                long startUpDelayMs, boolean enabled,
                                List<String> libs, String childGatingJson,
                                String scriptMode, String scriptName, String cipherName,
                                String cipherBytesBase64, String cipherSha256,
                                String gadgetJson, String key,
                                boolean allowEnabledEdit, boolean allowDelayEdit)
            throws SubConfigException {
        if (appName == null || appName.trim().isEmpty()) {
            throw new SubConfigException("目标包名为空");
        }
        if (key == null || !ScriptCrypto.keyIsAscii(key)) {
            throw new SubConfigException("密钥必须是 ASCII（XOR 用的是 charCodeAt）");
        }
        if (MODE_PLAIN.equals(scriptMode)) {
            throw new SubConfigException(
                    "明文直推模式下没有可保护的密文。\n请先把「脚本处理方式」改成"
                            + "「明文 → 管理器加密」或「密文 → 设备内解密」，再导出子配置。");
        }

        TreeSet<String> editable = new TreeSet<>();
        if (allowEnabledEdit) {
            editable.add(EDIT_ENABLED);
        }
        if (allowDelayEdit) {
            editable.add(EDIT_DELAY);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("magic", MAGIC);
        fields.put("version", String.valueOf(VERSION));
        fields.put("created", String.valueOf(System.currentTimeMillis() / 1000L));
        fields.put("app_name", appName);
        fields.put("app_label", appLabel == null ? "" : appLabel);
        fields.put("editable", join(new ArrayList<>(editable), ","));
        fields.put("enabled", enabled ? "true" : "false");
        fields.put("delay_ms", String.valueOf(Math.max(0L, startUpDelayMs)));
        fields.put("libs", join(libs == null ? new ArrayList<String>() : libs, ";"));
        fields.put("child_gating", childGatingJson == null ? "" : childGatingJson);
        fields.put("script_mode", scriptMode);
        fields.put("script_name", scriptName == null ? "" : scriptName);
        fields.put("cipher_name", cipherName == null ? "" : cipherName);
        fields.put("cipher_sha256", cipherSha256 == null ? "" : cipherSha256);
        fields.put("cipher_b64", cipherBytesBase64 == null ? "" : cipherBytesBase64);
        fields.put("gadget", gadgetJson == null ? "" : gadgetJson);

        byte[] macKey = derive(appName, "mac");
        fields.put("sig", sign(canonical(fields), macKey));
        byte[] aesKey = derive(appName, "aes");
        fields.put("key_sealed", seal(key.getBytes(UTF8), aesKey));

        StringBuilder sb = new StringBuilder();
        sb.append("# ZygiskFrida manager sub config - one app per file.\n");
        sb.append("# Locked fields must not be modified; the signature covers every field.\n");
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(e.getKey()).append('=').append(escape(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ parse

    public static Imported parse(String text) throws SubConfigException {
        if (text == null || text.trim().isEmpty()) {
            throw new SubConfigException("文件是空的");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String rawLine : text.split("\n", -1)) {
            // Only the key may be trimmed: values are part of the signature, and
            // a value really can end in whitespace (the gadget json does).
            String line = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String head = line.trim();
            if (head.isEmpty() || head.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            fields.put(line.substring(0, eq).trim(), unescape(line.substring(eq + 1)));
        }

        if (!MAGIC.equals(fields.get("magic"))) {
            throw new SubConfigException("不是 ZygiskFrida 子配置文件（缺少 magic 标记）");
        }
        String version = fields.get("version");
        if (!String.valueOf(VERSION).equals(version)) {
            throw new SubConfigException("子配置版本不支持: " + version + "（本机支持 " + VERSION + "）");
        }
        String appName = orEmpty(fields.get("app_name"));
        if (appName.isEmpty()) {
            throw new SubConfigException("缺少 app_name");
        }

        String sig = orEmpty(fields.get("sig"));
        byte[] macKey = derive(appName, "mac");
        String expected = sign(canonical(fields), macKey);
        if (!expected.equalsIgnoreCase(sig)) {
            throw new SubConfigException(
                    "签名校验失败：这个文件被改动过（锁定的内容不允许修改）。\n"
                            + "如果你只是在接收端按允许范围改了延时/启用状态，那是改在本机，"
                            + "不需要也不会改动这个文件。");
        }

        Imported out = new Imported();
        out.appName = appName;
        out.appLabel = orEmpty(fields.get("app_label"));
        out.enabled = !"false".equalsIgnoreCase(orEmpty(fields.get("enabled")));
        out.startUpDelayMs = parseLong(orEmpty(fields.get("delay_ms")));
        String libs = orEmpty(fields.get("libs"));
        if (!libs.isEmpty()) {
            out.libs.addAll(Arrays.asList(libs.split(";")));
        }
        out.childGatingJson = orEmpty(fields.get("child_gating"));
        out.scriptMode = orEmpty(fields.get("script_mode"));
        if (out.scriptMode.isEmpty()) {
            out.scriptMode = MODE_PLAIN;
        }
        out.scriptName = orEmpty(fields.get("script_name"));
        out.cipherName = orEmpty(fields.get("cipher_name"));
        out.cipherSha256 = orEmpty(fields.get("cipher_sha256"));
        String cipherB64 = orEmpty(fields.get("cipher_b64"));
        out.gadgetJson = orEmpty(fields.get("gadget"));
        String editable = orEmpty(fields.get("editable"));
        if (!editable.isEmpty()) {
            for (String e : editable.split(",")) {
                if (!e.trim().isEmpty()) {
                    out.editable.add(e.trim());
                }
            }
        }

        byte[] aesKey = derive(appName, "aes");
        try {
            out.key = new String(unseal(orEmpty(fields.get("key_sealed")), aesKey), UTF8);
        } catch (Exception e) {
            throw new SubConfigException("密钥解封失败（文件损坏或不是本管理器导出的）: " + e);
        }
        if (!ScriptCrypto.keyIsAscii(out.key)) {
            throw new SubConfigException("解封出来的密钥不是 ASCII，拒绝导入");
        }

        if (!cipherB64.isEmpty()) {
            byte[] decoded = ScriptCrypto.base64Decode(cipherB64);
            if (decoded == null || decoded.length == 0) {
                throw new SubConfigException("密文不是合法 Base64");
            }
            out.cipherBase64 = cipherB64;
            String got = sha256HexOfText(cipherB64);
            if (!out.cipherSha256.isEmpty() && !got.equalsIgnoreCase(out.cipherSha256)) {
                throw new SubConfigException("密文与文件里的 sha256 不一致，文件已损坏");
            }
        }
        return out;
    }

    /** True when the file at least looks like one of ours. */
    public static boolean looksLikeSubConfig(String text) {
        return text != null && text.contains("magic=" + MAGIC);
    }

    /** The digest recorded for the embedded payload (over the base64 text). */
    public static String sha256HexOfText(String text) throws SubConfigException {
        return hex(sha256((text == null ? "" : text).getBytes(UTF8)));
    }

    // ------------------------------------------------------------------ crypto

    private static byte[] derive(String appName, String purpose) throws SubConfigException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] once = md.digest((APP_SECRET + "|" + purpose + "|" + appName + "|" + MAGIC)
                    .getBytes(UTF8));
            // a couple of extra rounds so a dumped constant is not directly the key
            for (int i = 0; i < 512; i++) {
                once = md.digest(once);
            }
            return once;
        } catch (Exception e) {
            throw new SubConfigException("密钥派生失败: " + e);
        }
    }

    private static String seal(byte[] plain, byte[] aesKey) throws SubConfigException {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain);
            ByteArrayOutputStream out = new ByteArrayOutputStream(iv.length + ct.length);
            out.write(iv);
            out.write(ct);
            return ScriptCrypto.base64Encode(out.toByteArray());
        } catch (Exception e) {
            throw new SubConfigException("密钥封存失败: " + e);
        }
    }

    private static byte[] unseal(String sealed, byte[] aesKey) throws Exception {
        byte[] all = ScriptCrypto.base64Decode(sealed);
        if (all == null || all.length < 13) {
            throw new IllegalArgumentException("sealed key too short");
        }
        byte[] iv = Arrays.copyOfRange(all, 0, 12);
        byte[] ct = Arrays.copyOfRange(all, 12, all.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(128, iv));
        return cipher.doFinal(ct);
    }

    private static String sign(String canonical, byte[] macKey) throws SubConfigException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(UTF8)));
        } catch (Exception e) {
            throw new SubConfigException("签名失败: " + e);
        }
    }

    /**
     * Everything except the signature itself, in a fixed order - so a single
     * changed character anywhere invalidates it.
     *
     * {@code key_sealed} is skipped as well: it is produced after signing and
     * carries its own AES-GCM authentication tag (tampering there fails the
     * unseal step instead).
     */
    private static String canonical(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if ("sig".equals(e.getKey()) || "key_sealed".equals(e.getKey())) {
                continue;
            }
            sb.append(e.getKey()).append('\u0001').append(e.getValue()).append('\u0002');
        }
        return sb.toString();
    }

    private static byte[] sha256(byte[] data) throws SubConfigException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new SubConfigException("sha256 不可用: " + e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ text

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                if (n == 'n') {
                    sb.append('\n');
                } else if (n == 'r') {
                    sb.append('\r');
                } else {
                    sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /** Suggested file name for an export, e.g. {@code myheroes.zfmcfg}. */
    public static String suggestFileName(String appName, String appLabel) {
        String base = (appLabel == null || appLabel.trim().isEmpty()) ? appName : appLabel.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        String name = sb.toString();
        if (name.isEmpty()) {
            name = "subconfig";
        }
        return name + FILE_SUFFIX;
    }

    /** One line summary of what the recipient may change. */
    public static String summarizeEditable(Set<String> editable) {
        List<String> parts = new ArrayList<>();
        if (editable.contains(EDIT_DELAY)) {
            parts.add("延时");
        }
        if (editable.contains(EDIT_ENABLED)) {
            parts.add("启用状态");
        }
        Collections.sort(parts);
        return parts.isEmpty() ? "全部锁定" : join(parts, "、");
    }
}
