package re.zyg.fri.manager;

import android.content.Context;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Writes the local target list (and optionally the gadget config) to the device.
 *
 * Everything goes through {@code /data/local/tmp/re.zyg.fri} because that is the
 * configuration directory of the externally installed ZygiskFrida module.
 */
public final class Pusher {

    public static final class Outcome {
        public boolean ok;
        public final StringBuilder log = new StringBuilder();

        void line(String s) {
            log.append(s).append('\n');
        }

        public String text() {
            return log.toString();
        }
    }

    private Pusher() {
    }

    /**
     * Writes only the gadget config.
     *
     * Used right after a script has been picked: the user just chose a file and
     * expects the gadget to actually load it, without having to remember a
     * second "push" step.
     */
    public static synchronized Shell.Result writeGadgetConfig(Context ctx) {
        Prefs p = Prefs.get(ctx);
        if (!p.gadgetConfigEnabled()) {
            return new Shell.Result(0, "未启用 gadget 配置写入", "", "(skipped)", false);
        }
        String json = p.gadgetConfigJson();
        if (!GadgetHelper.isValidJson(json)) {
            return new Shell.Result(-1, "", "gadget 配置不是合法 JSON", "(validate)", false);
        }
        if (!Shell.ensureDir(ctx, p.moduleDir())) {
            return new Shell.Result(-1, "", "无法创建模块目录 " + p.moduleDir(),
                    "(mkdir)", false);
        }
        DeviceConfigTransaction transaction = new DeviceConfigTransaction(ctx, p.moduleDir());
        Shell.Result result = transaction.begin();
        if (!result.ok()) return result;
        result = transaction.stageText(p.gadgetConfigPath(), json, "0644");
        if (!result.ok()) {
            transaction.abort();
            return result;
        }
        return transaction.commit();
    }

    public static synchronized Outcome apply(Context ctx) {
        Prefs p = Prefs.get(ctx);
        ConfigStore store = ConfigStore.get(ctx);
        Outcome out = new Outcome();
        out.ok = true;

        String moduleDir = p.moduleDir();
        String scriptsDir = p.scriptsDir();

        out.line("模块目录: " + moduleDir);
        if (!Shell.ensureDir(ctx, moduleDir)) {
            out.line("[x] 无法创建/访问模块目录，ZygiskFrida 模块装好了吗？");
            out.ok = false;
            return out;
        }
        out.line("[+] 模块目录就绪");

        if (Shell.ensureDir(ctx, scriptsDir)) {
            out.line("[+] 脚本目录就绪: " + scriptsDir);
        }
        final String desiredFingerprint = DeploymentState.fingerprint(ctx);

        DeviceConfigTransaction transaction = new DeviceConfigTransaction(ctx, moduleDir);
        Shell.Result started = transaction.begin();
        if (!started.ok()) {
            out.line("[x] 无法创建配置事务暂存目录");
            out.line(started.dump());
            out.ok = false;
            return out;
        }

        // Build and stage the complete desired state before touching live files.
        String json;
        try {
            JSONArray targets = new JSONArray();
            ScriptStore scripts = new ScriptStore(ctx);
            for (TargetConfig target : new ArrayList<>(store.targets())) {
                if (target.scriptId.isEmpty() || !target.enabled) {
                    targets.put(target.toJson());
                    continue;
                }
                if (!scripts.contains(target.scriptId)) {
                    throw new IllegalArgumentException(target.appName + ": 指定的脚本已不存在");
                }
                Prefs selected = Prefs.forScript(ctx, target.scriptId);
                if (!selected.gadgetConfigEnabled() || !GadgetHelper.isValidJson(selected.gadgetConfigJson())) {
                    throw new IllegalArgumentException(scripts.name(target.scriptId) + ": 配置未启用或 JSON 不合法");
                }
                JSONObject interaction = new JSONObject(selected.gadgetConfigJson()).optJSONObject("interaction");
                if (interaction == null) throw new IllegalArgumentException("缺少 interaction 配置");
                if ("script".equals(interaction.optString("type"))
                        && !Shell.exists(ctx, interaction.optString("path"))) {
                    throw new IllegalArgumentException(scripts.name(target.scriptId) + ": 脚本文件不存在，请先导入脚本");
                }
                List<String> gadgets = Arrays.asList(p.gadgetPath(), p.gadget32Path(), p.childGadgetPath());
                InjectionPlan plan = new InjectionPlan(target, moduleDir, gadgets);
                for (InjectionPlan.Copy copy : plan.copies) {
                    Shell.Result copied = transaction.stageCopy(copy.source, copy.destination, "0755");
                    if (!copied.ok()) throw new IllegalStateException(copied.dump());
                    String config = GadgetHelper.configPathFor(copy.destination);
                    Shell.Result written = transaction.stageText(config, selected.gadgetConfigJson(), "0644");
                    if (!written.ok()) throw new IllegalStateException(written.dump());
                }
                targets.put(plan.target.toJson());
                out.line("[+] " + target.appName + " → " + scripts.name(target.scriptId));
            }
            json = GadgetHelper.prettify(new JSONObject().put("targets", targets)) + "\n";
        } catch (Exception error) {
            transaction.abort();
            out.ok = false;
            out.line("[x] 准备配置事务失败，设备现有配置未改变: " + error.getMessage());
            return out;
        }
        Shell.Result stagedConfig = transaction.stageText(p.configPath(), json, "0644");
        if (!stagedConfig.ok()) {
            transaction.abort();
            out.line("[x] 暂存 config.json 失败，设备现有配置未改变");
            out.line(stagedConfig.dump());
            out.ok = false;
            return out;
        }
        if (p.gadgetConfigEnabled()) {
            Shell.Result stagedGadget = transaction.stageText(
                    p.gadgetConfigPath(), p.gadgetConfigJson(), "0644");
            if (!stagedGadget.ok()) {
                transaction.abort();
                out.line("[x] 暂存默认 Gadget 配置失败，设备现有配置未改变");
                out.line(stagedGadget.dump());
                out.ok = false;
                return out;
            }
        }
        if (!desiredFingerprint.equals(DeploymentState.fingerprint(ctx))) {
            transaction.abort();
            out.line("[x] 推送过程中本机配置发生变化，已取消提交，请重新推送");
            out.ok = false;
            return out;
        }

        Shell.Result committed = transaction.commit();
        if (!committed.ok() || !committed.out().contains("__ZFM_COMMIT_OK__")) {
            out.line("[x] 提交失败，事务已尝试恢复原文件");
            out.line(committed.dump());
            out.ok = false;
            return out;
        }
        out.line("[+] 配置事务提交完成，整组文件已更新");
        out.line("[+] config.json 已写入 (" + json.getBytes(Shell.UTF8).length + " 字节, "
                + store.targets().size() + " 个目标)");
        if (p.gadgetConfigEnabled()) {
            out.line("[+] gadget 配置已写入 " + p.gadgetConfigPath());
            out.line("    " + GadgetHelper.summarize(p.gadgetConfigJson()));
        }

        String readBack = Shell.readTextFile(ctx, p.configPath());
        if (readBack == null || !readBack.trim().equals(json.trim())) {
            out.line("[!] 提交后读回校验异常，未记录为已推送");
            out.ok = false;
            return out;
        }
        DeploymentState.markApplied(ctx, desiredFingerprint);
        out.line("[+] 读回校验通过，已记录当前配置版本");

        // ------------------------------------------------------------ sanity
        if (!Shell.exists(ctx, p.gadgetPath())) {
            out.line("[!] 找不到 gadget: " + p.gadgetPath());
            out.line("    模块自带的是 libgadget.so / libgadget32.so；"
                    + "也可以在“工具”里导入自定义 gadget。");
        } else {
            long size = Shell.sizeOf(ctx, p.gadgetPath());
            out.line("[+] gadget 就绪: " + p.gadgetPath() + " (" + size + " 字节)");
        }

        out.line("");
        out.line("现在启动目标应用即可生效。日志: logcat -s ZygiskFrida");
        return out;
    }
}
