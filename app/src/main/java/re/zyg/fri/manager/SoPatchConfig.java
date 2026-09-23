package re.zyg.fri.manager;

import org.json.JSONException;
import org.json.JSONObject;

/** Configuration validation shared by the editor and the export pipeline. */
public final class SoPatchConfig {
    public static final String GADGET_NAME = "libgadget.so";
    public static final String CONFIG_NAME = "libgadget.config.so";
    public static final String SCRIPT_NAME = "libgadget.script.so";

    private SoPatchConfig() {}

    public static String preset(int mode, String address, int port) throws JSONException {
        JSONObject interaction = new JSONObject();
        switch (mode) {
            case 0:
            case 1:
                interaction.put("type", "listen").put("address", address).put("port", port)
                        .put("on_port_conflict", "pick-next").put("on_load", mode == 1 ? "wait" : "resume");
                break;
            case 2:
                interaction.put("type", "script").put("path", SCRIPT_NAME);
                break;
            case 3:
                interaction.put("type", "connect").put("address", address).put("port", port);
                break;
            default: throw new JSONException("未知配置预设");
        }
        return new JSONObject().put("interaction", interaction).toString(2).replace("\\/", "/");
    }

    public static String validate(String json, boolean attachedScript) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject interaction = root.optJSONObject("interaction");
        if (interaction == null) throw new JSONException("配置缺少 interaction 对象");
        String type = interaction.optString("type");
        if ("listen".equals(type) || "connect".equals(type)) {
            if (attachedScript) throw new JSONException("监听或连接模式不能附带自动脚本，请使用自动脚本模式");
            Object host = interaction.opt("address");
            if (host != null && !(host instanceof String)) throw new JSONException("地址必须是字符串");
            String address = interaction.optString("address", "127.0.0.1").trim();
            if (address.isEmpty()) throw new JSONException("地址不能为空");
            Object value = interaction.opt("port");
            if (value != null && (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                    || ((Number) value).intValue() < 1 || ((Number) value).intValue() > 65535))
                throw new JSONException("端口必须是 1 到 65535 的整数");
            String onLoad = interaction.optString("on_load", "resume");
            if (!"wait".equals(onLoad) && !"resume".equals(onLoad)) throw new JSONException("on_load 只能是 wait 或 resume");
            String conflict = interaction.optString("on_port_conflict", "pick-next");
            if (!"pick-next".equals(conflict) && !"fail".equals(conflict)) throw new JSONException("端口冲突策略无效");
        } else if ("script".equals(type) || "script-directory".equals(type)) {
            if (!(interaction.opt("path") instanceof String)) throw new JSONException("脚本 path 必须是字符串");
            String path = interaction.optString("path", "").trim();
            if (path.isEmpty() || path.indexOf('\0') >= 0) throw new JSONException("脚本配置缺少有效的 path");
            if (attachedScript && (!"script".equals(type) || !SCRIPT_NAME.equals(path)))
                throw new JSONException("附带脚本时，interaction.path 必须为 " + SCRIPT_NAME);
            if (!attachedScript && SCRIPT_NAME.equals(path)) throw new JSONException("请先选择要随补丁包导出的脚本");
        } else {
            throw new JSONException("不支持的 interaction.type：" + type);
        }
        return root.toString(2).replace("\\/", "/") + "\n";
    }

    public static String validateSoName(String name) {
        if (name == null || !name.startsWith("lib") || !name.endsWith(".so") || name.length() > 180
                || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
            throw new IllegalArgumentException("请选择名称为 lib*.so 的库文件，文件名不能包含路径");
        if (GADGET_NAME.equals(name) || CONFIG_NAME.equals(name) || SCRIPT_NAME.equals(name))
            throw new IllegalArgumentException("请选择目标应用的 SO，不能选择 Gadget、配置或附带脚本文件");
        return name;
    }
}
