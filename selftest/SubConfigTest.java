import re.zyg.fri.manager.ScriptCrypto;
import re.zyg.fri.manager.SubConfig;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM side self test for {@link SubConfig} - no Android, no device.
 *
 * Checks the three things that matter for handing a config to somebody else:
 * the key must not be readable in the file, locked fields must not be editable
 * without detection, and an untouched file must round trip exactly.
 */
public class SubConfigTest {

    private static int pass;
    private static int fail;

    private static void check(String label, boolean ok) {
        check(label, ok, null);
    }

    private static void check(String label, boolean ok, String extra) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + label);
        } else {
            fail++;
            System.out.println("  FAIL  " + label + (extra == null ? "" : "  -> " + extra));
        }
    }

    public static void main(String[] args) throws Exception {
        String key = "justcrackmenow";
        // a small fake "payload" so we do not need the real 1 MB one here
        String plain = "// demo script\nsend({ k: 'demo' });\n";
        String cipherB64 = ScriptCrypto.encrypt(plain, key);
        String cipherSha = sha256Hex(cipherB64.getBytes("UTF-8"));

        String app = "com.r2games.myhero.aligames";
        List<String> libs = new ArrayList<>(Arrays.asList(
                "/data/local/tmp/re.zyg.fri/libgadget.so"));
        String gadget = "{\"interaction\":{\"type\":\"script\",\"path\":\"/data/local/tmp/"
                + "re.zyg.fri/scripts/x.loader.js\",\"on_change\":\"reload\"}}";

        String file = SubConfig.export(app, "\u6211\u7684\u52c7\u8005", 120000L, true,
                libs, "", "decrypt-device", "mh_index.js", "mh_index.so",
                cipherB64, cipherSha, gadget, key, true, false);

        System.out.println("[1] exported file");
        System.out.println("        " + file.length() + " chars, "
                + file.split("\n").length + " lines");

        check("key does not appear as text in the file", file.indexOf(key) < 0);
        check("key does not appear base64 encoded either",
                file.indexOf(ScriptCrypto.base64Encode(key.getBytes("UTF-8"))) < 0);
        check("plaintext payload does not appear in the file",
                file.indexOf("demo script") < 0);
        check("looksLikeSubConfig()", SubConfig.looksLikeSubConfig(file));

        System.out.println("[2] importing an untouched file");
        SubConfig.Imported in = SubConfig.parse(file);
        check("app name", app.equals(in.appName), in.appName);
        check("label survives (utf-8)", "\u6211\u7684\u52c7\u8005".equals(in.appLabel));
        check("delay", in.startUpDelayMs == 120000L, String.valueOf(in.startUpDelayMs));
        check("enabled", in.enabled);
        check("libs", in.libs.equals(libs), in.libs.toString());
        check("script mode", "decrypt-device".equals(in.scriptMode), in.scriptMode);
        check("cipher name", "mh_index.so".equals(in.cipherName), in.cipherName);
        check("cipher sha256", cipherSha.equalsIgnoreCase(in.cipherSha256));
        check("key recovered", key.equals(in.key));
        check("cipher payload recovered", cipherB64.equals(in.cipherBase64));
        check("gadget json recovered", gadget.equals(in.gadgetJson));
        check("editable = enabled only",
                in.canEdit(SubConfig.EDIT_ENABLED) && !in.canEdit(SubConfig.EDIT_DELAY),
                in.editable.toString());

        System.out.println("[3] tampering with a locked field must be rejected");
        String tampered = file.replace("delay_ms=120000", "delay_ms=1");
        check("delay was actually modified in the test string",
                !tampered.equals(file));
        try {
            SubConfig.parse(tampered);
            check("tampered file rejected", false, "parse() did not throw");
        } catch (SubConfig.SubConfigException e) {
            check("tampered file rejected", true);
            System.out.println("        " + e.getMessage().split("\n")[0]);
        }

        System.out.println("[4] tampering with the script name must be rejected");
        try {
            SubConfig.parse(file.replace("script_name=mh_index.js", "script_name=evil.js"));
            check("tampered script_name rejected", false, "parse() did not throw");
        } catch (SubConfig.SubConfigException e) {
            check("tampered script_name rejected", true);
        }

        System.out.println("[5] plaintext mode must refuse to export");
        try {
            SubConfig.export(app, "x", 0, true, libs, "", "plain", "a.js", "a.js",
                    null, null, gadget, key, false, false);
            check("plain mode rejected", false, "export() did not throw");
        } catch (SubConfig.SubConfigException e) {
            check("plain mode rejected", true);
        }

        System.out.println("[6] file name suggestion");
        check("suggestFileName strips path characters",
                SubConfig.suggestFileName("com.a/b", "my app").equals("my_app.zfmcfg"),
                SubConfig.suggestFileName("com.a/b", "my app"));

        System.out.println("[7] values with trailing whitespace survive the round trip");
        // regression: the stored gadget json ends with a newline + spaces, and an
        // earlier parser trimmed the whole line, which broke the signature
        String gadgetPadded = gadget + "\n    ";
        String padded = SubConfig.export(app, "x", 5000L, true, libs, "", "decrypt-device",
                "a.js", "a.so", cipherB64, cipherSha, gadgetPadded, key, false, true);
        try {
            SubConfig.Imported again = SubConfig.parse(padded);
            check("parse ok with padded gadget", gadgetPadded.equals(again.gadgetJson));
            check("delay still parsed", again.startUpDelayMs == 5000L);
        } catch (SubConfig.SubConfigException e) {
            check("parse ok with padded gadget", false, e.getMessage().split("\n")[0]);
        }
        // and tampering must still be caught in that shape
        try {
            SubConfig.parse(padded.replace("delay_ms=5000", "delay_ms=6000"));
            check("padded file tamper still rejected", false, "parse() did not throw");
        } catch (SubConfig.SubConfigException e) {
            check("padded file tamper still rejected", true);
        }

        if (args.length > 0) {
            Files.write(Paths.get(args[0], "demo" + SubConfig.FILE_SUFFIX),
                    file.getBytes("UTF-8"));
            System.out.println("        sample written to " + args[0] + "/demo"
                    + SubConfig.FILE_SUFFIX);
        }

        System.out.println("\u7ed3\u679c: pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
