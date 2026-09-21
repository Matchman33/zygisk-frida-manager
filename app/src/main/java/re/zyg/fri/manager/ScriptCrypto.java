package re.zyg.fri.manager;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/**
 * XOR + Base64 script encryption, byte compatible with the myheroes-pro tooling
 * (cry.js {@code encrypt2()}):
 *
 * <pre>
 *   bytes     = utf8(plaintext)
 *   xored[i]  = bytes[i] ^ key.charCodeAt(i % key.length)
 *   output    = base64(xored)          // one single line, e.g. dist/index.so
 * </pre>
 *
 * Deliberately free of any Android API (base64 is hand rolled) so the exact same
 * code can be exercised on a plain JVM - see the round trip test in the repo
 * history: decrypting a real index.so must reproduce the original bundle
 * character for character.
 */
public final class ScriptCrypto {

    public static final String DEFAULT_KEY = "justcrackmenow";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String B64_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private ScriptCrypto() {
    }

    // ------------------------------------------------------------------ base64

    /** @return decoded bytes, or null when the input is not base64 at all */
    public static byte[] base64Decode(String input) {
        if (input == null) {
            return null;
        }
        int length = input.length();
        byte[] buffer = new byte[length / 4 * 3 + 3];
        int out = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < length; i++) {
            char c = input.charAt(i);
            if (c == '=') {
                break;
            }
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            int v = B64_CHARS.indexOf(c);
            if (v < 0) {
                return null;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                buffer[out++] = (byte) ((acc >> bits) & 0xFF);
            }
        }
        byte[] result = new byte[out];
        System.arraycopy(buffer, 0, result, 0, out);
        return result;
    }

    public static String base64Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(B64_CHARS.charAt((n >> 18) & 63))
                    .append(B64_CHARS.charAt((n >> 12) & 63))
                    .append(B64_CHARS.charAt((n >> 6) & 63))
                    .append(B64_CHARS.charAt(n & 63));
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(B64_CHARS.charAt((n >> 18) & 63))
                    .append(B64_CHARS.charAt((n >> 12) & 63))
                    .append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(B64_CHARS.charAt((n >> 18) & 63))
                    .append(B64_CHARS.charAt((n >> 12) & 63))
                    .append(B64_CHARS.charAt((n >> 6) & 63))
                    .append('=');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ crypto

    public static byte[] xor(byte[] data, String key) {
        if (key == null || key.isEmpty()) {
            return data;
        }
        byte[] keyBytes = key.getBytes(UTF8);
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ keyBytes[i % keyBytes.length]);
        }
        return out;
    }

    /** Mirror of cry.js {@code encrypt2()}. */
    public static String encrypt(String plain, String key) {
        return base64Encode(xor(plain.getBytes(UTF8), key));
    }

    /** Seed used to obfuscate the key inside a generated loader. */
    public static final String LOADER_SEED = "ZFM-LOADER-V1";

    /**
     * The loader carries the key obfuscated, so a plain {@code grep} for the key
     * over /data/local/tmp does not find it. The loader reverses this at runtime.
     * Obfuscation, not secrecy - a reverse engineer can still recover it.
     */
    public static String obfuscateKey(String key) {
        return base64Encode(xor(key.getBytes(UTF8), LOADER_SEED));
    }

    /**
     * Mirror of the client side decryption.
     *
     * @return the plaintext, or null when the data is not valid base64 or does
     * not decode to valid UTF-8 (which in practice means "wrong key")
     */
    public static String decrypt(String cipherText, String key) {
        byte[] raw = base64Decode(cipherText == null ? null : cipherText.trim());
        if (raw == null || raw.length == 0) {
            return null;
        }
        byte[] plain = xor(raw, key);
        try {
            return UTF8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Heuristic: a single line of base64, long enough to be a payload. */
    public static boolean looksEncrypted(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() < 64 || (t.length() & 3) != 0) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (B64_CHARS.indexOf(c) < 0 && c != '=' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** XOR uses charCodeAt(), so keys outside ASCII would not round trip. */
    public static boolean keyIsAscii(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ loader

    /**
     * The little plain-text script the gadget actually loads.
     *
     * Reads the ciphertext and decrypts it, or reads a plain script, then hands
     * the result to Frida. Diagnostics go to logcat; the payload owns its UI.
     */
    public static final class Bootstrap {

        public boolean encrypted;
        /** device path of the (encrypted) file to read */
        public String source = "";
        /** device path of the plain script, used when {@link #encrypted} is false */
        public String plain = "";
        public String key = "";
        /** name the script is registered under */
        public String name = "script.js";
        /** logcat tag for loading diagnostics */
        public String logTag = "ZFM";
        public String msgStart = "ZygiskFrida 已注入";
        public String msgReady = "脚本已加载";
        public String msgFail = "脚本加载失败: ";

        public String render() {
            String[] lines = new String[]{
                    "/* ZygiskFrida manager - generated loader. Do not edit. */",
                    "(function () {",
                    "  var CFG = {",
                    "    encrypted: " + (encrypted ? "true" : "false") + ",",
                    "    source: \"" + js(source) + "\",",
                    "    plain: \"" + js(plain) + "\",",
                    "    keyObf: \"" + js(obfuscateKey(key)) + "\",",
                    "    name: \"" + js(name) + "\",",
                    "    logTag: \"" + js(logTag) + "\",",
                    "    msgStart: \"" + js(msgStart) + "\",",
                    "    msgReady: \"" + js(msgReady) + "\",",
                    "    msgFail: \"" + js(msgFail) + "\"",
                    "  };",
                    "  function consoleLog(m) { try { console.log(m); } catch (e) { } }",
                    "  // Write diagnostics through liblog without requiring a Java bridge.",
                    "  function logcat(m) {",
                    "    try {",
                    "      var addr = null;",
                    "      try {",
                    "        if (typeof Module.getGlobalExportByName === 'function') {",
                    "          addr = Module.getGlobalExportByName('__android_log_write');",
                    "        }",
                    "      } catch (e) { }",
                    "      try {",
                    "        if (!addr && typeof Module.findGlobalExportByName === 'function') {",
                    "          addr = Module.findGlobalExportByName('__android_log_write');",
                    "        }",
                    "      } catch (e) { }",
                    "      try {",
                    "        if (!addr && typeof Module.getExportByName === 'function') {",
                    "          addr = Module.getExportByName('liblog.so', '__android_log_write');",
                    "        }",
                    "      } catch (e) { }",
                    "      try {",
                    "        if (!addr) {",
                    "          var lib = Process.getModuleByName('liblog.so');",
                    "          if (lib && typeof lib.getExportByName === 'function') {",
                    "            addr = lib.getExportByName('__android_log_write');",
                    "          }",
                    "        }",
                    "      } catch (e) { }",
                    "      if (!addr) { return false; }",
                    "      var write = new NativeFunction(addr, 'int', ['int', 'pointer', 'pointer']);",
                    "      write(4, Memory.allocUtf8String(CFG.logTag), Memory.allocUtf8String(m));",
                    "      return true;",
                    "    } catch (e) { return false; }",
                    "  }",
                    "  function log(m) {",
                    "    consoleLog('[ZFM] ' + m);",
                    "    logcat('[ZFM] ' + m);",
                    "  }",
                    "  function readText(path) {",
                    "    var f = new File(path, 'r');",
                    "    try { return f.readText(); } finally { try { f.close(); } catch (e) { } }",
                    "  }",
                    "  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';",
                    "  function b64(s) {",
                    "    var out = [], acc = 0, bits = 0, i, c, v;",
                    "    for (i = 0; i < s.length; i++) {",
                    "      c = s.charAt(i);",
                    "      if (c === '=') { break; }",
                    "      v = B64.indexOf(c);",
                    "      if (v < 0) { continue; }",
                    "      acc = (acc << 6) | v;",
                    "      bits += 6;",
                    "      if (bits >= 8) { bits -= 8; out.push((acc >> bits) & 255); }",
                    "    }",
                    "    return out;",
                    "  }",
                    "  function xorBytes(bytes, key) {",
                    "    var out = new Array(bytes.length), i;",
                    "    for (i = 0; i < bytes.length; i++) {",
                    "      out[i] = bytes[i] ^ key.charCodeAt(i % key.length);",
                    "    }",
                    "    return out;",
                    "  }",
                    "  // the key travels obfuscated, never in clear text",
                    "  function deobfuscateKey(obf) {",
                    "    var seed = 'ZFM-LOADER-V1';",
                    "    var bytes = b64(obf);",
                    "    var s = '';",
                    "    for (var i = 0; i < bytes.length; i++) {",
                    "      s += String.fromCharCode(bytes[i] ^ seed.charCodeAt(i % seed.length));",
                    "    }",
                    "    return s;",
                    "  }",
                    "  function utf8(bytes) {",
                    "    var s = '', i = 0, c, c2, c3, c4, cp;",
                    "    while (i < bytes.length) {",
                    "      c = bytes[i++];",
                    "      if (c < 128) { s += String.fromCharCode(c); }",
                    "      else if (c < 224) {",
                    "        c2 = bytes[i++]; s += String.fromCharCode(((c & 31) << 6) | (c2 & 63));",
                    "      } else if (c < 240) {",
                    "        c2 = bytes[i++]; c3 = bytes[i++];",
                    "        s += String.fromCharCode(((c & 15) << 12) | ((c2 & 63) << 6) | (c3 & 63));",
                    "      } else {",
                    "        c2 = bytes[i++]; c3 = bytes[i++]; c4 = bytes[i++];",
                    "        cp = (((c & 7) << 18) | ((c2 & 63) << 12) | ((c3 & 63) << 6) | (c4 & 63)) - 65536;",
                    "        s += String.fromCharCode(55296 + (cp >> 10), 56320 + (cp & 1023));",
                    "      }",
                    "    }",
                    "    return s;",
                    "  }",
                    "  function loadScript(name, src) {",
                    "    if (typeof Script !== 'undefined' && Script !== null",
                    "        && typeof Script.load === 'function') {",
                    "      try { Script.load(name, src); return true; }",
                    "      catch (e) { log('Script.load failed: ' + e); }",
                    "    }",
                    "    try { (new Function(src))(); return true; }",
                    "    catch (e) { log('eval failed: ' + e); return false; }",
                    "  }",
                    "  function main() {",
                    "    log(CFG.msgStart);",
                    "    var src;",
                    "    if (CFG.encrypted) {",
                    "      var raw = readText(CFG.source);",
                    "      if (!raw || raw.length === 0) { throw new Error('密文为空: ' + CFG.source); }",
                    "      src = utf8(xorBytes(b64(raw), deobfuscateKey(CFG.keyObf)));",
                    "      log('decrypted ' + src.length + ' chars from ' + CFG.source);",
                    "    } else {",
                    "      src = readText(CFG.plain);",
                    "      if (!src || src.length === 0) { throw new Error('脚本为空: ' + CFG.plain); }",
                    "      log('read ' + src.length + ' chars from ' + CFG.plain);",
                    "    }",
                    "    var ok = loadScript(CFG.name, src);",
                    "    log(ok ? CFG.msgReady : CFG.msgFail + 'see logcat -s ' + CFG.logTag);",
                    "    log('done: ok=' + ok);",
                    "  }",
                    "  try { main(); }",
                    "  catch (e) { log('bootstrap error: ' + e); log(CFG.msgFail + e); }",
                    "})();",
                    "",
            };
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static String js(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
