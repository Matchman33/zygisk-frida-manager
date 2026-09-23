package re.zyg.fri.manager;

import android.app.Dialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.widget.TooltipCompat;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Environment check, config viewer and file import.
 *
 * The output box is a raw root shell, so anything the UI does not cover can
 * still be done by hand.
 */
public class ToolsActivity extends BaseActivity {

    private static final int REQ_IMPORT_GADGET = 5001;
    private static final int REQ_IMPORT_SCRIPT = 5002;
    private static final int REQ_CRYPTO_IN = 5003;
    private static final int REQ_CRYPTO_OUT = 5004;
    private static final int REQ_SUBCONFIG = 5005;

    private static final String[] SU_MODES = {
            Prefs.SU_AUTO, Prefs.SU_C, Prefs.SU_0_SH_C};

    private interface Job {
        String run();
    }

    private Prefs prefs;
    private ConfigStore store;
    private EditText moduleDirInput;
    private Spinner suModeSpinner;
    private TextView outputText;
    private ScrollView outputScroll;

    private boolean cryptoEncrypt;
    private String cryptoKey = "";
    private String cryptoInText = "";
    private String cryptoOutText = "";
    private String cryptoOutName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        store = ConfigStore.get(this);
        setContentView(R.layout.activity_tools);
        findViewById(R.id.patchSoButton).setOnClickListener(v -> startActivity(new Intent(this, PatchSoActivity.class)));

        moduleDirInput = findViewById(R.id.moduleDirInput);
        suModeSpinner = findViewById(R.id.suModeSpinner);
        outputText = findViewById(R.id.outputText);
        outputScroll = findViewById(R.id.outputScroll);
        findViewById(R.id.settingsToggle).setOnClickListener(v -> {
            View advanced = findViewById(R.id.advancedTools);
            boolean expand = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(expand ? View.VISIBLE : View.GONE);
            v.setContentDescription(getString(R.string.section_advanced) + (expand ? "，已展开" : "，已收起"));
        });

        moduleDirInput.setText(prefs.moduleDir());

        String[] suLabels = new String[]{
                getString(R.string.su_auto),
                getString(R.string.su_c),
                getString(R.string.su_0_sh_c)};
        ArrayAdapter<String> modes = new ArrayAdapter<>(this,
                R.layout.item_spinner, suLabels);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        suModeSpinner.setAdapter(modes);
        suModeSpinner.setSelection(indexOf(SU_MODES, prefs.suMode()));
        suModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.setSuMode(SU_MODES[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.saveModuleDirButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String dir = moduleDirInput.getText().toString().trim();
                if (!dir.startsWith("/")) {
                    Ui.toast(ToolsActivity.this, "必须是绝对路径");
                    return;
                }
                prefs.setModuleDir(dir);
                Ui.toast(ToolsActivity.this, "模块目录已保存: " + prefs.moduleDir());
                append("模块目录 = " + prefs.moduleDir()
                        + "\n  config.json = " + prefs.configPath()
                        + "\n  gadget      = " + prefs.gadgetPath()
                        + "\n  gadget cfg  = " + prefs.gadgetConfigPath());
            }
        });

        findViewById(R.id.testRootButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("测试 root（su -c id）", new Job() {
                    @Override
                    public String run() {
                        Shell.Result r = Shell.rootCheck(ToolsActivity.this, true);
                        return r.dump();
                    }
                });
            }
        });
        findViewById(R.id.envReportButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("环境自检", new Job() {
                    @Override
                    public String run() {
                        return DeviceEnv.fullReport(ToolsActivity.this);
                    }
                });
            }
        });
        findViewById(R.id.viewConfigButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("查看设备上的 config.json", new Job() {
                    @Override
                    public String run() {
                        String json = Shell.readTextFile(ToolsActivity.this, prefs.configPath());
                        if (json == null) {
                            return "读取失败: " + prefs.configPath() + "（不存在或没有 root）";
                        }
                        return json;
                    }
                });
            }
        });
        findViewById(R.id.listDirButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("目录列表 " + prefs.moduleDir(), new Job() {
                    @Override
                    public String run() {
                        return DeviceEnv.listDir(ToolsActivity.this, prefs.moduleDir());
                    }
                });
            }
        });
        findViewById(R.id.outputToggle).setOnClickListener(v -> outputScroll.setVisibility(
                outputScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        TooltipCompat.setTooltipText(findViewById(R.id.clearOutputButton), getString(R.string.action_clear));
        findViewById(R.id.clearOutputButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                outputText.setText("");
            }
        });
        findViewById(R.id.customCommandButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.promptText(ToolsActivity.this, "以 root 执行命令", "", new Ui.TextCallback() {
                    @Override
                    public void onText(final String value) {
                        if (value.isEmpty()) {
                            return;
                        }
                        run(value, new Job() {
                            @Override
                            public String run() {
                                return Shell.su(ToolsActivity.this, value, null,
                                        Shell.TIMEOUT_LONG).dump();
                            }
                        });
                    }
                });
            }
        });
        findViewById(R.id.importGadgetButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(REQ_IMPORT_GADGET);
            }
        });
        findViewById(R.id.importScriptButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(REQ_IMPORT_SCRIPT);
            }
        });
        findViewById(R.id.encryptScriptButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCrypto(true);
            }
        });
        findViewById(R.id.decryptScriptButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCrypto(false);
            }
        });

        findViewById(R.id.importSubConfigButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(REQ_SUBCONFIG);
            }
        });

        outputText.setHint("暂无输出");
    }

    // ------------------------------------------------------------------ sub config

    /**
     * Applies a sub config somebody handed over: the payload travels inside the
     * file, the key is unsealed only to build the loader and stays hidden, and
     * everything the exporter locked is kept read only.
     */
    private void importSubConfig(final Uri uri) {
        final Dialog busy = Ui.busy(this, "正在导入子配置…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                String error = null;
                String report = null;
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) {
                        throw new java.io.IOException("无法读取所选文件");
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                    in.close();
                    String text = new String(bos.toByteArray(), Shell.UTF8);

                    SubConfig.Imported sub = SubConfig.parse(text);
                    ScriptStore.Entry entry = new ScriptStore(ctx()).create(
                            sub.appLabel.isEmpty() ? sub.appName : sub.appLabel);
                    Prefs prefs = Prefs.forScript(ctx(), entry.id);

                    // ---- target
                    TargetConfig t = store.find(sub.appName);
                    if (t == null) {
                        t = new TargetConfig(sub.appName);
                    } else {
                        t = t.copy();
                    }
                    t.appLabel = sub.appLabel;
                    t.enabled = sub.enabled;
                    t.startUpDelayMs = sub.startUpDelayMs;
                    t.injectedLibraries.clear();
                    t.injectedLibraries.addAll(sub.libs);
                    t.childGatingEnabled = false;
                    t.childGatingLibraries.clear();
                    if (!sub.childGatingJson.isEmpty()) {
                        JSONObject cg = new JSONObject(sub.childGatingJson);
                        t.childGatingEnabled = cg.optBoolean("enabled", false);
                        t.childGatingMode = TargetConfig.normalizeMode(
                                cg.optString("mode", TargetConfig.MODE_FREEZE));
                        JSONArray arr = cg.optJSONArray("injected_libraries");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject lib = arr.optJSONObject(i);
                                if (lib != null && !lib.optString("path", "").isEmpty()) {
                                    t.childGatingLibraries.add(lib.optString("path"));
                                }
                            }
                        }
                    }
                    t.locked.clear();
                    t.lock(TargetConfig.LOCK_APP);
                    t.lock(TargetConfig.LOCK_LIBRARIES);
                    t.lock(TargetConfig.LOCK_CHILD_GATING);
                    t.lock(TargetConfig.LOCK_SCRIPT);
                    t.scriptId = entry.id;
                    if (!sub.canEdit(SubConfig.EDIT_DELAY)) {
                        t.lock(TargetConfig.LOCK_DELAY);
                    }

                    // ---- script (key stays hidden from now on)
                    prefs.setScriptMode(sub.scriptMode);
                    prefs.setScriptKey(sub.key);
                    prefs.setScriptKeyHidden(true);
                    prefs.setScriptLockedBySubConfig(true);
                    prefs.setScriptName(sub.scriptName);

                    String cipherDevice = prefs.scriptsDir() + "/" + sub.cipherName;
                    if (!Shell.ensureDir(ctx(), prefs.scriptsDir())) {
                        throw new java.io.IOException("无法创建脚本目录");
                    }
                    StringBuilder log = new StringBuilder();
                    if (!sub.cipherBase64.isEmpty()) {
                        File tmp = new File(ctx().getCacheDir(), sub.cipherName);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                        try {
                            fos.write((sub.cipherBase64 + "\n").getBytes(Shell.UTF8));
                        } finally {
                            fos.close();
                        }
                        Shell.Result pushed = Shell.pushFile(ctx(), tmp, cipherDevice, "0644");
                        tmp.delete();
                        if (!pushed.ok()) {
                            throw new java.io.IOException("密文推送失败:\n" + pushed.dump());
                        }
                        prefs.setScriptCipherPath(cipherDevice);
                        log.append("[+] 密文已写入 ").append(cipherDevice).append('\n');
                    }

                    String loaderDevice = prefs.scriptsDir() + "/"
                            + Scripts.baseName(sub.cipherName) + ".loader.js";
                    String js = Scripts.buildLoaderFor(prefs, sub.scriptName, cipherDevice);
                    Shell.Result wrote = Shell.writeTextFile(ctx(), loaderDevice, js);
                    if (!wrote.ok()) {
                        throw new java.io.IOException("loader 写入失败:\n" + wrote.dump());
                    }
                    Shell.chmod(ctx(), loaderDevice, "0644");
                    Shell.chownRoot(ctx(), loaderDevice);
                    prefs.setScriptPath(loaderDevice);
                    prefs.setGadgetConfigJson(
                            Scripts.wireGadgetToScript(sub.gadgetJson, loaderDevice));
                    prefs.setGadgetConfigEnabled(true);
                    log.append("[+] loader 已生成 ").append(loaderDevice).append('\n');

                    store.put(t);
                    Pusher.Outcome outcome = Pusher.apply(ctx());
                    log.append(outcome.text());

                    report = "导入完成: " + sub.appName
                            + (sub.appLabel.isEmpty() ? "" : "（" + sub.appLabel + "）") + "\n"
                            + "对方允许修改: " + sub.editableText() + "\n"
                            + "已锁定: " + SubConfig.summarizeEditable(
                                    lockedComplement(sub)) + "\n"
                            + "密钥: 已导入并隐藏（界面不显示、不可修改）\n\n"
                            + log;
                } catch (SubConfig.SubConfigException e) {
                    error = e.getMessage();
                } catch (Exception e) {
                    error = e.toString();
                }
                final String err = error;
                final String out = report;
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (err != null) {
                            Ui.report(ToolsActivity.this, "导入失败", err);
                            return;
                        }
                        append(out == null ? "导入完成" : out);
                        Ui.report(ToolsActivity.this, "子配置已导入",
                                out == null ? "导入完成" : out);
                    }
                });
            }
        });
    }

    private static java.util.Set<String> lockedComplement(SubConfig.Imported sub) {
        java.util.Set<String> all = new java.util.TreeSet<>();
        all.add(SubConfig.EDIT_DELAY);
        all.add(SubConfig.EDIT_ENABLED);
        all.removeAll(sub.editable);
        return all;
    }

    private android.content.Context ctx() {
        return this;
    }

    private void append(String text) {
        outputScroll.setVisibility(View.VISIBLE);
        outputText.append(text + "\n\n");
        outputScroll.post(new Runnable() {
            @Override
            public void run() {
                outputScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void run(final String label, final Job job) {
        append("> " + label);
        final Dialog busy = Ui.busy(this, label + " …");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final String result;
                try {
                    result = job.run();
                } catch (Throwable t) {
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            busy.dismiss();
                            append("任务异常: " + t);
                        }
                    });
                    return;
                }
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        append(result);
                    }
                });
            }
        });
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ import

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, requestCode);
        } catch (Throwable t) {
            Ui.toast(this, "没有可用的文件选择器: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_GADGET) {
            importGadget(uri);
        } else if (requestCode == REQ_IMPORT_SCRIPT) {
            importScript(uri);
        } else if (requestCode == REQ_CRYPTO_IN) {
            transformCrypto(uri);
        } else if (requestCode == REQ_CRYPTO_OUT) {
            writeCryptoResult(uri);
        } else if (requestCode == REQ_SUBCONFIG) {
            importSubConfig(uri);
        }
    }

    // ------------------------------------------------------------------ crypto

    /** Standalone encrypt / decrypt tool: pick a file, transform it, save the result. */
    private void startCrypto(final boolean encrypt) {
        Ui.promptText(this,
                encrypt ? "加密脚本（明文 → XOR+Base64）" : "解密脚本（XOR+Base64 → 明文）",
                prefs.scriptKey(),
                new Ui.TextCallback() {
                    @Override
                    public void onText(String value) {
                        if (value.isEmpty()) {
                            Ui.toast(ToolsActivity.this, "密钥不能为空");
                            return;
                        }
                        if (!ScriptCrypto.keyIsAscii(value)) {
                            Ui.toast(ToolsActivity.this, "密钥必须是 ASCII 字符（XOR 用的是 charCodeAt）");
                            return;
                        }
                        cryptoEncrypt = encrypt;
                        cryptoKey = value;
                        append("> " + (encrypt ? "加密" : "解密") + "脚本，密钥 " + value);
                        pickFile(REQ_CRYPTO_IN);
                    }
                });
    }

    private void transformCrypto(Uri uri) {
        final Dialog busy = Ui.busy(this, cryptoEncrypt ? "正在加密…" : "正在解密…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                String name = Scripts.displayName(ToolsActivity.this, uri);
                String base = Scripts.baseName(name);
                String error = null;
                String inText = null;
                String outText = null;
                String outName = null;
                try {
                    File local = Scripts.copyToCache(ToolsActivity.this, uri, name);
                    inText = Scripts.readLocal(local);
                    if (cryptoEncrypt) {
                        if (ScriptCrypto.looksEncrypted(inText)) {
                            error = "这个文件看起来已经是密文（单行 Base64），不需要再加密";
                        } else {
                            String cipher = ScriptCrypto.encrypt(inText, cryptoKey);
                            if (!inText.equals(ScriptCrypto.decrypt(cipher, cryptoKey))) {
                                error = "加密自检失败（解回来不一致），已中止";
                            } else {
                                outText = cipher;
                                outName = base + ".so";
                            }
                        }
                    } else {
                        String plain = ScriptCrypto.decrypt(inText, cryptoKey);
                        if (plain == null) {
                            error = "解密失败：文件不是 XOR+Base64，或者密钥不对";
                        } else {
                            outText = plain;
                            outName = base + ".js";
                        }
                    }
                    local.delete();
                } catch (IOException e) {
                    error = e.toString();
                }
                final String err = error;
                final String in = inText;
                final String out = outText;
                final String outNm = outName;
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (err != null) {
                            append("[x] " + err);
                            return;
                        }
                        cryptoInText = in;
                        cryptoOutText = out;
                        cryptoOutName = outNm;
                        saveCryptoResult();
                    }
                });
            }
        });
    }

    private void saveCryptoResult() {
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/octet-stream");
        create.putExtra(Intent.EXTRA_TITLE, cryptoOutName);
        try {
            startActivityForResult(create, REQ_CRYPTO_OUT);
        } catch (Throwable t) {
            append("[x] 没有可用的保存选择器: " + t);
        }
    }

    private void writeCryptoResult(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                append("[x] 无法写入所选位置");
                return;
            }
            byte[] bytes = cryptoOutText.getBytes(Shell.UTF8);
            try {
                os.write(bytes);
            } finally {
                os.close();
            }
            boolean selfCheck = cryptoEncrypt
                    ? cryptoInText.equals(ScriptCrypto.decrypt(cryptoOutText, cryptoKey))
                    : cryptoInText.trim().equals(ScriptCrypto.encrypt(cryptoOutText, cryptoKey));
            StringBuilder sb = new StringBuilder();
            sb.append(cryptoEncrypt ? "[+] 加密完成" : "[+] 解密完成");
            sb.append("\n    输入 ").append(cryptoInText.length())
                    .append(" 字符 → 输出 ").append(cryptoOutText.length())
                    .append(" 字符（").append(bytes.length).append(" 字节）");
            sb.append("\n    保存到: ").append(uri);
            sb.append("\n    密钥: ").append(cryptoKey);
            sb.append("\n    自检: ").append(selfCheck
                    ? (cryptoEncrypt ? "解回来与原文一致" : "再加密与原密文一致")
                    : "不一致 [!]");
            append(sb.toString());
        } catch (IOException e) {
            append("[x] 写入失败: " + e);
        }
    }

    private void importScript(Uri uri) {
        ScriptStore.Entry entry = new ScriptStore(this).create(Scripts.displayName(this, uri));
        Prefs selected = Prefs.forScript(this, entry.id);
        final Dialog busy = Ui.busy(this, "正在推送脚本…");
        busy.show();
        Scripts.importScript(this, uri, selected, true, new Scripts.Callback() {
            @Override
            public void onDone(String devicePath, String error, String note) {
                busy.dismiss();
                if (error != null) {
                    append("[x] 脚本推送失败:\n" + error);
                    return;
                }
                StringBuilder sb = new StringBuilder("[+] 脚本已推送: " + devicePath);
                if (note != null && !note.isEmpty()) {
                    sb.append("\n    ").append(note);
                }
                sb.append("\n    已加入脚本列表: ").append(entry.name);
                append(sb.toString());
            }
        });
    }

    private void importGadget(final Uri uri) {
        final Dialog busy = Ui.busy(this, "正在推送 gadget…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                String name = Scripts.displayName(ToolsActivity.this, uri);
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".so")) {
                    name = "lib" + name + ".so";
                }
                final String devicePath = prefs.moduleDir() + "/" + name;
                String error = null;
                try {
                    File local = Scripts.copyToCache(ToolsActivity.this, uri, name);
                    if (!Shell.ensureDir(ToolsActivity.this, prefs.moduleDir())) {
                        error = "无法创建模块目录 " + prefs.moduleDir();
                    } else {
                        Shell.Result r = Shell.pushFile(ToolsActivity.this, local, devicePath,
                                "0644");
                        if (!r.ok()) {
                            error = r.dump();
                        }
                    }
                    local.delete();
                } catch (IOException e) {
                    error = e.toString();
                }
                final String err = error;
                final String finalName = name;
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (err != null) {
                            append("[x] gadget 推送失败:\n" + err);
                            return;
                        }
                        append("[+] gadget 已推送: " + devicePath);
                        new MaterialAlertDialogBuilder(ToolsActivity.this)
                                .setTitle("设为当前 gadget？")
                                .setMessage("把 " + finalName + " 作为注入目标使用的 gadget？")
                                .setPositiveButton("是",
                                        new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(
                                                    android.content.DialogInterface dialog,
                                                    int which) {
                                                prefs.setGadgetFile(finalName);
                                                append("当前 gadget = " + prefs.gadgetPath());
                                            }
                                        })
                                .setNegativeButton("否", null)
                                .show();
                    }
                });
            }
        });
    }
}
