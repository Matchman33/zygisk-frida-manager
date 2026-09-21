package re.zyg.fri.manager;

import org.json.JSONObject;
import java.util.Iterator;

/**
 * Frida gadget configuration helpers.
 *
 * The gadget reads its config from a file next to the library itself: for
 * {@code /data/local/tmp/re.zyg.fri/libgadget.so} that is
 * {@code /data/local/tmp/re.zyg.fri/libgadget.config.so}.
 *
 * See https://frida.re/docs/gadget/ for the full schema.
 */
public final class GadgetHelper {

    public static final String MODE_LISTEN = "listen";
    public static final String MODE_SCRIPT = "script";
    public static final String MODE_CONNECT = "connect";

    private GadgetHelper() {
    }

    /**
     * org.json escapes every '/' as '\/'. That is legal JSON and rapidjson
     * accepts it, but it makes the file on the device unreadable for a human,
     * so the escape is undone everywhere we serialise.
     */
    public static String prettify(JSONObject object) {
        try {
            return object.toString(4).replace("\\/", "/");
        } catch (Exception e) {
            return object.toString();
        }
    }

    /** {@code libgadget.so -> libgadget.config.so} */
    public static String configPathFor(String gadgetPath) {
        if (gadgetPath == null || gadgetPath.isEmpty()) {
            return "";
        }
        if (gadgetPath.endsWith(".so")) {
            return gadgetPath.substring(0, gadgetPath.length() - 3) + ".config.so";
        }
        return gadgetPath + ".config.so";
    }

    /** Prints a script path on every fork. */
    public static String buildScriptConfig(String scriptPath, boolean onLoadWait,
                                           boolean reloadOnChange) {
        JSONObject interaction = new JSONObject();
        try {
            interaction.put("type", MODE_SCRIPT);
            interaction.put("path", scriptPath);
            if (reloadOnChange) {
                interaction.put("on_change", "reload");
            }
            if (onLoadWait) {
                interaction.put("on_load", "wait");
            }
        } catch (Exception ignored) {
        }
        return wrap(interaction);
    }

    /** Listens on a local port; you connect with `frida -U -n Gadget`. */
    public static String buildListenConfig(String address, int port, boolean onLoadWait,
                                           String onPortConflict) {
        JSONObject interaction = new JSONObject();
        try {
            interaction.put("type", MODE_LISTEN);
            interaction.put("address", address == null || address.isEmpty()
                    ? "127.0.0.1" : address);
            interaction.put("port", port <= 0 ? 27042 : port);
            interaction.put("on_port_conflict",
                    onPortConflict == null || onPortConflict.isEmpty() ? "pick-next" : onPortConflict);
            interaction.put("on_load", onLoadWait ? "wait" : "resume");
        } catch (Exception ignored) {
        }
        return wrap(interaction);
    }

    /** The gadget actively connects back to a frida host. */
    public static String buildConnectConfig(String address, int port, boolean onLoadWait) {
        JSONObject interaction = new JSONObject();
        try {
            interaction.put("type", MODE_CONNECT);
            interaction.put("address", address == null || address.isEmpty()
                    ? "127.0.0.1" : address);
            interaction.put("port", port <= 0 ? 27042 : port);
            interaction.put("on_load", onLoadWait ? "wait" : "resume");
        } catch (Exception ignored) {
        }
        return wrap(interaction);
    }

    private static String wrap(JSONObject interaction) {
        JSONObject root = new JSONObject();
        try {
            root.put("interaction", interaction);
            return prettify(root) + "\n";
        } catch (Exception e) {
            return "{}\n";
        }
    }

    /** One line description used in lists. */
    public static String summarize(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "(空)";
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONObject it = root.optJSONObject("interaction");
            if (it == null) {
                return "无 interaction 段";
            }
            String type = it.optString("type", "?");
            StringBuilder sb = new StringBuilder(type);
            if (MODE_SCRIPT.equals(type)) {
                sb.append(" → ").append(Ui.shortPath(it.optString("path", "?"), 40));
            } else {
                sb.append(" ").append(it.optString("address", "?")).append(':')
                        .append(it.optInt("port", 0));
            }
            String onLoad = it.optString("on_load", "");
            if (!onLoad.isEmpty()) {
                sb.append(" · on_load=").append(onLoad);
            }
            return sb.toString();
        } catch (Exception e) {
            return "JSON 解析失败";
        }
    }

    public static boolean isValidJson(String json) {
        try {
            new JSONObject(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Keep advanced fields when the form only edits the common settings. */
    public static String mergeForm(String original, String generated) {
        try {
            JSONObject root = new JSONObject(original);
            JSONObject edited = new JSONObject(generated).getJSONObject("interaction");
            JSONObject previous = root.optJSONObject("interaction");
            if (previous != null && previous.optString("type").equals(edited.optString("type"))) {
                for (String key : new String[]{"type", "address", "port", "on_load",
                        "on_port_conflict", "path", "on_change"}) {
                    previous.remove(key);
                }
                Iterator<String> keys = edited.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    previous.put(key, edited.get(key));
                }
                edited = previous;
            }
            root.put("interaction", edited);
            return prettify(root);
        } catch (Exception ignored) {
            return generated;
        }
    }

    public static String pretty(String json) {
        try {
            return prettify(new JSONObject(json)) + "\n";
        } catch (Exception e) {
            return json;
        }
    }
}
