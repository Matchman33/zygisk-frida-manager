package re.zyg.fri.manager;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted settings of the manager app.
 *
 * Everything here is about *where* things live, not about the injection targets
 * themselves (those are stored by {@link ConfigStore}).
 */
public final class Prefs {

    /** Directory the ZygiskFrida magisk module unpacks its gadget into. */
    public static final String DEFAULT_MODULE_DIR = "/data/local/tmp/re.zyg.fri";

    public static final String SU_AUTO = "auto";
    public static final String SU_C = "su-c";
    public static final String SU_0_SH_C = "su-0-sh-c";

    /** Default gadget config: wait for a manual `frida -U -n Gadget` connection. */
    public static final String DEFAULT_GADGET_CONFIG =
            "{\n"
                    + "  \"interaction\": {\n"
                    + "    \"type\": \"listen\",\n"
                    + "    \"address\": \"127.0.0.1\",\n"
                    + "    \"port\": 27042,\n"
                    + "    \"on_port_conflict\": \"pick-next\",\n"
                    + "    \"on_load\": \"wait\"\n"
                    + "  }\n"
                    + "}\n";

    private static Prefs instance;

    private final SharedPreferences sp;
    private final SharedPreferences global;
    private final String profileId;

    private Prefs(Context ctx) {
        this(ctx, "");
    }

    private Prefs(Context ctx, String id) {
        profileId = id;
        global = ctx.getApplicationContext().getSharedPreferences("zygiskfrida", Context.MODE_PRIVATE);
        sp = id.isEmpty() ? global : ctx.getApplicationContext()
                .getSharedPreferences("script_" + id, Context.MODE_PRIVATE);
    }

    public static Prefs forScript(Context ctx, String id) {
        if (id == null || id.isEmpty()) return get(ctx);
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid script ID");
        return new Prefs(ctx, id);
    }

    public String profileId() {
        return profileId;
    }

    public static synchronized Prefs get(Context ctx) {
        if (instance == null) {
            instance = new Prefs(ctx);
        }
        return instance;
    }

    private String str(String key, String def) {
        String v = sp.getString(key, def);
        return v == null ? def : v;
    }

    // ---------------------------------------------------------------- module dir

    public String moduleDir() {
        String v = global.getString("module_dir", DEFAULT_MODULE_DIR).trim();
        while (v.endsWith("/") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    public void setModuleDir(String dir) {
        sp.edit().putString("module_dir", dir.trim()).apply();
    }

    public String configPath() {
        return moduleDir() + "/config.json";
    }

    public String scriptsDir() {
        return moduleDir() + "/scripts" + (profileId.isEmpty() ? "" : "/" + profileId);
    }

    public String gadgetFile() {
        return global.getString("gadget_file", "libgadget.so");
    }

    public void setGadgetFile(String name) {
        sp.edit().putString("gadget_file", name.trim()).apply();
    }

    public String gadgetPath() {
        return moduleDir() + "/" + gadgetFile();
    }

    public String gadget32Path() {
        return moduleDir() + "/" + global.getString("gadget32_file", "libgadget32.so");
    }

    public String childGadgetPath() {
        return moduleDir() + "/" + global.getString("child_gadget_file", "libgadget-child.so");
    }

    // ---------------------------------------------------------------- su

    public String suMode() {
        return str("su_mode", SU_AUTO);
    }

    public void setSuMode(String mode) {
        sp.edit().putString("su_mode", mode).apply();
    }

    // ---------------------------------------------------------------- gadget config

    public boolean gadgetConfigEnabled() {
        return sp.getBoolean("gadget_config_enabled", false);
    }

    public void setGadgetConfigEnabled(boolean enabled) {
        sp.edit().putBoolean("gadget_config_enabled", enabled).apply();
    }

    public String gadgetConfigJson() {
        return str("gadget_config_json", DEFAULT_GADGET_CONFIG);
    }

    public void setGadgetConfigJson(String json) {
        sp.edit().putString("gadget_config_json", json).apply();
    }

    /** Empty means "derive from the gadget path". */
    public String gadgetConfigPathOverride() {
        return str("gadget_config_path", "");
    }

    public void setGadgetConfigPathOverride(String path) {
        sp.edit().putString("gadget_config_path", path == null ? "" : path.trim()).apply();
    }

    public String gadgetConfigPath() {
        String override = gadgetConfigPathOverride();
        if (!override.isEmpty()) {
            return override;
        }
        return GadgetHelper.configPathFor(gadgetPath());
    }

    // ---------------------------------------------------------------- misc

    public String scriptPath() {
        return str("script_path", scriptsDir() + "/script.js");
    }

    public void setScriptPath(String path) {
        sp.edit().putString("script_path", path == null ? "" : path.trim()).apply();
    }

    /** name of the file the user last imported, for display only */
    public String scriptName() {
        return str("script_name", "");
    }

    public void setScriptName(String name) {
        sp.edit().putString("script_name", name == null ? "" : name.trim()).apply();
    }

    /** the imported script is plain js and is pushed as is */
    public static final String MODE_PLAIN = "plain";
    /** the imported script is XOR+Base64 and the manager decrypts it before pushing */
    public static final String MODE_DECRYPT_LOCAL = "decrypt-local";
    /** the imported script is XOR+Base64 and stays encrypted on the device (loader decrypts) */
    public static final String MODE_DECRYPT_DEVICE = "decrypt-device";
    /** the imported script is plain and the manager encrypts it, so only ciphertext reaches the device */
    public static final String MODE_ENCRYPT_DEVICE = "encrypt-device";

    /** how an imported script is prepared before it is pushed */
    public String scriptMode() {
        String mode = sp.getString("script_mode", null);
        if (mode != null) {
            return mode;
        }
        // migrate from the first version of this feature (two separate checkboxes)
        if (!sp.getBoolean("script_enc", false)) {
            return MODE_PLAIN;
        }
        return sp.getBoolean("script_decrypt_on_device", true)
                ? MODE_DECRYPT_DEVICE : MODE_DECRYPT_LOCAL;
    }

    public void setScriptMode(String mode) {
        sp.edit().putString("script_mode", mode).apply();
    }

    /** true when the XOR+Base64 path is involved at all */
    public boolean scriptUsesCrypto() {
        return !MODE_PLAIN.equals(scriptMode());
    }

    public String scriptKey() {
        return str("script_key", ScriptCrypto.DEFAULT_KEY);
    }

    public void setScriptKey(String key) {
        sp.edit().putString("script_key", key == null ? "" : key.trim()).apply();
    }

    /**
     * True when the key came from an imported sub config: the UI must never
     * render or edit it, only use it internally.
     */
    public boolean scriptKeyHidden() {
        return sp.getBoolean("script_key_hidden", false);
    }

    public void setScriptKeyHidden(boolean hidden) {
        sp.edit().putBoolean("script_key_hidden", hidden).apply();
    }

    /** True when the whole script / key / gadget section is locked by a sub config. */
    public boolean scriptLockedBySubConfig() {
        return sp.getBoolean("script_locked_subconfig", false);
    }

    public void setScriptLockedBySubConfig(boolean locked) {
        sp.edit().putBoolean("script_locked_subconfig", locked).apply();
    }

    /** device path of the encrypted payload the loader reads ("" when none) */
    public String scriptCipherPath() {
        return str("script_cipher_path", "");
    }

    public void setScriptCipherPath(String path) {
        sp.edit().putString("script_cipher_path", path == null ? "" : path.trim()).apply();
    }

    public boolean showSystemApps() {
        return sp.getBoolean("show_system_apps", false);
    }

    public void setShowSystemApps(boolean show) {
        sp.edit().putBoolean("show_system_apps", show).apply();
    }

    public long lastDelayMs() {
        return sp.getLong("last_delay_ms", 0L);
    }

    public void setLastDelayMs(long ms) {
        sp.edit().putLong("last_delay_ms", Math.max(0L, ms)).apply();
    }

    /** directory of the last picked APK / script, used to seed the file picker */
    public String lastDir() {
        return str("last_dir", "");
    }

    public void setLastDir(String dir) {
        sp.edit().putString("last_dir", dir == null ? "" : dir).apply();
    }
}
