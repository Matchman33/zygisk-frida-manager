import re.zyg.fri.manager.ScriptCrypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * JVM side self test for {@link ScriptCrypto} - no Android, no device.
 *
 * Usage:
 *   java -cp out ScriptCryptoTest &lt;cipherFile&gt; &lt;expectedPlainFile&gt; &lt;key&gt; &lt;outDir&gt;
 *
 * Use --self-contained &lt;key&gt; &lt;outDir&gt; to generate a local fixture instead.
 */
public class ScriptCryptoTest {

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

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        boolean selfContained = args.length > 0 && "--self-contained".equals(args[0]);
        String key = selfContained
                ? (args.length > 1 ? args[1] : ScriptCrypto.DEFAULT_KEY)
                : (args.length > 2 ? args[2] : ScriptCrypto.DEFAULT_KEY);
        String outDir = selfContained
                ? (args.length > 2 ? args[2] : ".")
                : (args.length > 3 ? args[3] : ".");
        Path gen = Paths.get(outDir);
        Files.createDirectories(gen);

        Path cipher;
        String cipherText;
        String expected;
        if (selfContained) {
            expected = "// self-contained fixture\n"
                    + "globalThis.__ZFM_REAL_HIT = '\u81ea\u6d4b\u901a\u8fc7';\n";
            cipherText = ScriptCrypto.encrypt(expected, key);
            cipher = gen.resolve("index.so");
            Files.write(cipher, cipherText.getBytes("UTF-8"));
            Files.write(gen.resolve("expected.js"), expected.getBytes("UTF-8"));
        } else {
            if (args.length < 2) {
                System.out.println("FAIL  pass both cipher and plaintext paths");
                System.exit(1);
                return;
            }
            cipher = Paths.get(args[0]);
            Path plain = Paths.get(args[1]);
            if (!Files.isReadable(cipher) || !Files.isReadable(plain)) {
                System.out.println("FAIL  input files not found:");
                System.out.println("      " + cipher);
                System.out.println("      " + plain);
                System.exit(1);
                return;
            }
            cipherText = new String(Files.readAllBytes(cipher), "UTF-8");
            expected = new String(Files.readAllBytes(plain), "UTF-8");
        }

        System.out.println("[ScriptCrypto] cipher=" + cipherText.length()
                + " chars, expected=" + expected.length() + " chars, key=" + key);

        check("looksEncrypted(cipher) == true", ScriptCrypto.looksEncrypted(cipherText));
        check("looksEncrypted(plain) == false", !ScriptCrypto.looksEncrypted(expected));

        String decrypted = ScriptCrypto.decrypt(cipherText, key);
        check("decrypt() != null", decrypted != null);
        if (decrypted != null) {
            check("decrypt() == expected payload", decrypted.equals(expected));
            String a = sha256(decrypted.getBytes("UTF-8"));
            String b = sha256(expected.getBytes("UTF-8"));
            check("sha256 identical", a.equals(b), a + " vs " + b);
            System.out.println("        sha256 = " + a);
        }
        check("encrypt() round trips", ScriptCrypto.encrypt(expected, key)
                .equals(cipherText.trim()));
        check("wrong key -> null", ScriptCrypto.decrypt(cipherText, key + "x") == null);
        check("keyIsAscii(ascii) == true", ScriptCrypto.keyIsAscii(key));
        check("keyIsAscii(non-ascii) == false", !ScriptCrypto.keyIsAscii("\u5bc6\u94a5"));

        // the encrypt direction, including multi byte UTF-8 (the real payload has CJK in it)
        String cjk = "// \u4e2d\u6587\u6ce8\u91ca\nsend({ k: '\u4f60\u597d' });\n";
        String cjkCipher = ScriptCrypto.encrypt(cjk, "k123");
        check("encrypt() then decrypt() on CJK text", cjk.equals(
                ScriptCrypto.decrypt(cjkCipher, "k123")));
        check("ciphertext is a single line of base64", cjkCipher.indexOf('\n') < 0
                && cjkCipher.indexOf('\r') < 0);

        // render the loaders the manager would generate, so the Node harness can run them
        Files.copy(cipher, gen.resolve("index.so"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        ScriptCrypto.Bootstrap real = new ScriptCrypto.Bootstrap();
        real.encrypted = true;
        real.source = gen.toAbsolutePath().resolve("index.so").toString();
        real.key = key;
        real.name = "index.js";
        real.msgStart = "\u5df2\u6ce8\u5165 \u00b7 \u6b63\u5728\u52a0\u8f7d\u811a\u672c";
        real.msgReady = "\u811a\u672c\u5df2\u52a0\u8f7d: index.js";
        Files.write(gen.resolve("loader_real.js"), real.render().getBytes("UTF-8"));

        String small = "globalThis.__ZFM_HIT = 1;\n";
        Files.write(gen.resolve("small.enc"),
                ScriptCrypto.encrypt(small, "k123").getBytes("UTF-8"));
        ScriptCrypto.Bootstrap mini = new ScriptCrypto.Bootstrap();
        mini.encrypted = true;
        mini.source = gen.toAbsolutePath().resolve("small.enc").toString();
        mini.key = "k123";
        mini.name = "small.js";
        Files.write(gen.resolve("loader_small.js"), mini.render().getBytes("UTF-8"));

        Files.write(gen.resolve("plain.js"), small.getBytes("UTF-8"));
        ScriptCrypto.Bootstrap plainMode = new ScriptCrypto.Bootstrap();
        plainMode.encrypted = false;
        plainMode.plain = gen.toAbsolutePath().resolve("plain.js").toString();
        plainMode.name = "plain.js";
        Files.write(gen.resolve("loader_plain.js"), plainMode.render().getBytes("UTF-8"));

        String userScript = "Java.use('android.widget.Toast').makeText({}, 'USER_TOAST', 0).show();"
                + "globalThis.__USER_HIT = 1;";
        Files.write(gen.resolve("user.enc"), ScriptCrypto.encrypt(userScript, "k123").getBytes("UTF-8"));
        ScriptCrypto.Bootstrap user = new ScriptCrypto.Bootstrap();
        user.encrypted = true;
        user.source = gen.toAbsolutePath().resolve("user.enc").toString();
        user.key = "k123";
        Files.write(gen.resolve("loader_user.js"), user.render().getBytes("UTF-8"));

        String consoleScript = "'use strict'; console.log('hello', {n:7}); console.warn('warn-fixture');"
                + "console.error(new Error('error-fixture')); console.debug('debug-fixture');"
                + "const circular = {}; circular.self = circular; console.info(circular);"
                + "console.log('长'.repeat(2400)); globalThis.__CONSOLE_DONE = true;";
        check("Frida module bundle detected", ScriptCrypto.isModuleBundle("\uD83D\uDCE6\nmodule"));
        check("plain script is not a module bundle", !ScriptCrypto.isModuleBundle(consoleScript));
        Files.write(gen.resolve("console.js"), consoleScript.getBytes("UTF-8"));
        Files.write(gen.resolve("console.enc"), ScriptCrypto.encrypt(consoleScript, "k123").getBytes("UTF-8"));
        ScriptCrypto.Bootstrap console = new ScriptCrypto.Bootstrap();
        console.name = "console.js";
        console.plain = gen.toAbsolutePath().resolve("console.js").toString();
        Files.write(gen.resolve("loader_console_plain.js"), console.render().getBytes("UTF-8"));
        console.encrypted = true;
        console.key = "k123";
        console.source = gen.toAbsolutePath().resolve("console.enc").toString();
        Files.write(gen.resolve("loader_console_cipher.js"), console.render().getBytes("UTF-8"));

        System.out.println("        loaders written to " + gen.toAbsolutePath());
        System.out.println("结果: pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
