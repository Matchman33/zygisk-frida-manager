package re.zyg.fri.manager;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SoPatchEngine {
    public interface Progress { void update(String message); }

    public static final class Result {
        public final File archive;
        public final String filename;
        public final String config;
        public final String summary;
        Result(File archive, String filename, String config, String summary) {
            this.archive = archive; this.filename = filename; this.config = config; this.summary = summary;
        }
    }

    private SoPatchEngine() {}

    public static Result build(Context context, File input, String name, File script,
                               String json, File directory, Progress progress) throws Exception {
        SoPatchConfig.validateSoName(name);
        String config = SoPatchConfig.validate(json, script != null);
        progress.update("正在检查原始 SO…");
        ElfPatcher.Info before = ElfPatcher.inspect(input);
        String originalHash = BundledGadget.sha256(input);
        File patched = new File(directory, "patched.so");
        File gadget = new File(directory, "gadget.so");
        File configFile = new File(directory, "gadget-config.json");
        File receipt = new File(directory, "patch-result.json");
        File instructions = new File(directory, "README.txt");
        File archive = new File(directory, "patch.zip");
        try {
            progress.update("正在添加 Gadget 依赖…");
            ElfPatcher.Info after = ElfPatcher.patch(input, patched, SoPatchConfig.GADGET_NAME);
            if (!originalHash.equals(BundledGadget.sha256(input))) throw new IOException("原始 SO 意外发生变化，已中止");
            progress.update("正在解压并校验内置 Gadget…");
            BundledGadget bundled = new BundledGadget(context);
            bundled.extract(after.abi, gadget);
            write(configFile, config);
            JSONObject report = new JSONObject().put("format", 1).put("gadgetVersion", bundled.version())
                    .put("liefVersion", "1.0.0").put("architecture", after.abi).put("target", name)
                    .put("inputSha256", originalHash).put("patchedSha256", BundledGadget.sha256(patched))
                    .put("gadgetSha256", BundledGadget.sha256(gadget)).put("configSha256", BundledGadget.sha256(configFile))
                    .put("neededBefore", new JSONArray(before.needed)).put("neededAfter", new JSONArray(after.needed));
            if (script != null) report.put("scriptSha256", BundledGadget.sha256(script));
            write(receipt, report.toString(2));
            write(instructions, "ZygiskFrida 管理器 · SO 补丁包\n\n"
                    + "目标库：" + name + "\n架构：" + after.abi + "\nFrida Gadget：" + bundled.version() + "\n\n"
                    + "1. lib/" + after.abi + "/ 中包含补丁 SO、libgadget.so 和 libgadget.config.so。\n"
                    + "2. 将这些文件放入目标 APK 的同架构 lib 目录，替换对应目标 SO。\n"
                    + "3. 确保目标应用实际加载该 SO；只修改一个从未加载的库不会启动 Gadget。\n"
                    + "4. 使用 android:extractNativeLibs=true，让 Gadget 能读取旁边的配置和脚本文件。\n"
                    + "5. 回包后需要重新对齐与签名；原 APK 签名会失效，不能直接覆盖其他证书签名的安装。\n"
                    + "6. listen/connect 需要目标 APK 的 INTERNET 权限；wait 会阻塞到客户端连接。\n"
                    + "7. script 路径相对于 Gadget 所在目录解析。附带脚本时文件名为 libgadget.script.so。\n"
                    + "8. 本工具不自动回包、签名、安装 APK，也不自动调整目标应用的权限或其他架构的库。\n"
                    + "9. 既有预设中的绝对路径不一定在目标应用中可读，请检查配置内容。\n\n"
                    + "本工具保留原输入文件。patch-result.json 记录依赖与文件摘要。\n"
                    + "Frida 与 LIEF 来源及许可证见 licenses/。\n");
            String prefix = "lib/" + after.abi + "/";
            Map<String, File> files = new LinkedHashMap<>();
            files.put(prefix + name, patched);
            files.put(prefix + SoPatchConfig.GADGET_NAME, gadget);
            files.put(prefix + SoPatchConfig.CONFIG_NAME, configFile);
            if (script != null) files.put(prefix + SoPatchConfig.SCRIPT_NAME, script);
            files.put("patch-result.json", receipt);
            files.put("README.txt", instructions);
            progress.update("正在生成 ZIP 补丁包…");
            PatchArchiveWriter.write(context, files, archive);
            String exportName = name.substring(0, name.length() - 3) + "-" + after.abi + "-gadget-" + bundled.version() + ".zip";
            String summary = after.abi + " · " + (before.needed.contains(SoPatchConfig.GADGET_NAME)
                    ? "已存在 Gadget 依赖，未重复添加" : "已添加 libgadget.so 依赖")
                    + "\n包含补丁 SO、Gadget、配置" + (script == null ? "" : "及脚本") + "，原文件未修改。";
            return new Result(archive, exportName, config, summary);
        } catch (Exception error) {
            archive.delete();
            throw error;
        } finally {
            patched.delete(); gadget.delete(); configFile.delete(); receipt.delete(); instructions.delete();
        }
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
    }

    private static void write(File file, String value) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) { output.write(value.getBytes(StandardCharsets.UTF_8)); }
    }
}
