package re.zyg.fri.manager;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads everything interesting about the device side state, through one root shell. */
public final class DeviceEnv {

    public static final String MARKER = "##";

    private DeviceEnv() {
    }

    // ------------------------------------------------------------------ report

    /** Multi line, human readable environment report. */
    public static String fullReport(Context ctx) {
        Prefs p = Prefs.get(ctx);
        String md = p.moduleDir();

        StringBuilder script = new StringBuilder();
        script.append("echo '").append(MARKER).append("ID'; id 2>&1; ");
        script.append("echo '").append(MARKER).append("SU'; command -v su 2>&1 || echo 'PATH 中没有 su'; ");
        script.append("echo '").append(MARKER).append("MANAGERS'; ")
                .append("for d in magisk ksu ap apd; do [ -d /data/adb/$d ] && echo \"/data/adb/$d\"; done; ")
                .append("for b in magisk ksud apd; do c=$(command -v $b 2>/dev/null); [ -n \"$c\" ] && echo \"$c\"; done; ")
                .append("echo '(以上为检测到的 root 管理器痕迹)'; ");
        script.append("echo '").append(MARKER).append("MODULES'; ls -1 /data/adb/modules 2>&1; ");
        script.append("echo '").append(MARKER).append("ZFMODULE'; ls -1 /data/adb/modules/zygiskfrida 2>&1; ")
                .append("[ -f /data/adb/modules/zygiskfrida/disable ] && echo '模块状态: 已停用(disable 文件存在)' ")
                .append("|| echo '模块状态: 未停用'; ");
        script.append("echo '").append(MARKER).append("ZYGISKLIB'; ls -l /data/adb/modules/zygiskfrida/zygisk 2>&1; ");
        script.append("echo '").append(MARKER).append("DIR'; ls -la ").append(Shell.q(md)).append(" 2>&1; ");
        script.append("echo '").append(MARKER).append("CONFIG'; cat ").append(Shell.q(p.configPath())).append(" 2>&1; ");
        script.append("echo '").append(MARKER).append("GADGET'; ls -l ").append(Shell.q(p.gadgetPath())).append(" 2>&1; ");
        script.append("echo '").append(MARKER).append("ARCH'; od -An -tx1 -j18 -N2 ")
                .append(Shell.q(p.gadgetPath())).append(" 2>&1; ");
        script.append("echo '").append(MARKER).append("END'; ");

        Shell.Result r = Shell.su(ctx, script.toString(), null, Shell.TIMEOUT_LONG);
        Map<String, String> sections = parseSections(r.stdout);

        StringBuilder out = new StringBuilder();
        out.append("模块目录: ").append(md).append('\n');
        out.append("config.json: ").append(p.configPath()).append('\n');
        out.append("gadget: ").append(p.gadgetPath()).append('\n');
        out.append("gadget 配置: ").append(p.gadgetConfigPath())
                .append(p.gadgetConfigEnabled() ? "  [推送时写入]" : "  [不写入]").append("\n\n");

        appendSection(out, sections, "ID", "【Root 身份】");
        appendSection(out, sections, "MANAGERS", "【Root 管理器】");
        appendSection(out, sections, "MODULES", "【已安装 Magisk 模块】");
        appendSection(out, sections, "ZFMODULE", "【ZygiskFrida 模块】");
        appendSection(out, sections, "ZYGISKLIB", "【zygisk 库】");
        appendSection(out, sections, "DIR", "【模块目录】");
        appendSection(out, sections, "CONFIG", "【设备上的 config.json】");
        appendSection(out, sections, "GADGET", "【gadget 文件】");

        String archHex = sections.get("ARCH");
        out.append("【gadget 架构】\n").append(archName(archHex)).append('\n');

        if (!r.ok()) {
            out.append("\n[!] 部分命令返回 exit=").append(r.code).append('\n')
                    .append(r.err()).append('\n');
        }
        return out.toString();
    }

    private static void appendSection(StringBuilder out, Map<String, String> sections,
                                      String key, String title) {
        String body = sections.get(key);
        out.append(title).append('\n');
        out.append(body == null || body.trim().isEmpty() ? "(无输出)\n" : body.trim() + "\n");
        out.append('\n');
    }

    public static Map<String, String> parseSections(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        String current = null;
        StringBuilder buffer = new StringBuilder();
        String[] lines = raw.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith(MARKER)) {
                if (current != null) {
                    result.put(current, buffer.toString());
                }
                current = line.substring(MARKER.length()).trim();
                buffer.setLength(0);
            } else if (current != null) {
                buffer.append(line).append('\n');
            }
        }
        if (current != null) {
            result.put(current, buffer.toString());
        }
        return result;
    }

    public static String archName(String odOutput) {
        if (odOutput == null) {
            return "未知 (读取失败)";
        }
        String hex = odOutput.trim().replaceAll("\\s+", "");
        if (hex.length() < 4) {
            return "未知 (无输出)";
        }
        String machine = hex.substring(2, 4) + hex.substring(0, 2); // little endian
        if ("3e00".equals(machine)) {
            return "x86_64 (x64)";
        }
        if ("b700".equals(machine)) {
            return "aarch64 (arm64)";
        }
        if ("2800".equals(machine)) {
            return "arm (armeabi-v7a)";
        }
        if ("0300".equals(machine)) {
            return "i386 (x86)";
        }
        return "未知 e_machine=0x" + machine;
    }

    // ------------------------------------------------------------------ misc

    /** Compact one-liner for the main screen header. */
    public static String quickStatus(Context ctx) {
        Prefs p = Prefs.get(ctx);
        String script = "echo -n 'ROOT='; (id 2>/dev/null | grep -q 'uid=0' && echo ok || echo fail); "
                + "echo -n 'MODDIR='; ([ -d " + Shell.q(p.moduleDir()) + " ] && echo ok || echo missing); "
                + "echo -n 'GADGET='; ([ -f " + Shell.q(p.gadgetPath()) + " ] && echo ok || echo missing); "
                + "echo -n 'CONFIG='; ([ -f " + Shell.q(p.configPath()) + " ] && echo ok || echo missing)";
        Shell.Result r = Shell.su(ctx, script, null, Shell.TIMEOUT_FIRST);
        String text = r.out().replace('\n', ' ');
        if (text.isEmpty()) {
            return "状态检测失败: " + r.err();
        }
        return text;
    }

    public static String listDir(Context ctx, String dir) {
        Shell.Result r = Shell.su(ctx, "ls -la " + Shell.q(dir) + " 2>&1");
        return r.dump();
    }

    public static String logcatSnapshot(Context ctx, String tag, int lines) {
        Shell.Result r = Shell.su(ctx,
                "logcat -d -v time -t " + lines + " -s " + tag + " 2>&1");
        String out = r.out();
        return out.isEmpty() ? "(没有 " + tag + " 相关日志)" : out;
    }

    public static String readConfig(Context ctx) {
        String json = Shell.readTextFile(ctx, Prefs.get(ctx).configPath());
        return json == null ? "" : json;
    }
}
