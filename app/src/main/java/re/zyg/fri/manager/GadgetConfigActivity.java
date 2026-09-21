package re.zyg.fri.manager;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.json.JSONObject;

/**
 * Editors for the Frida gadget configuration.
 *
 * The gadget reads {@code <libgadget>.config.so} next to the library, so this
 * screen decides whether a target waits for a manual `frida -U -n Gadget`
 * connection or auto-loads a script picked from the phone.
 */
public class GadgetConfigActivity extends BaseActivity {
    public static final String EXTRA_SCRIPT_ID = "script_id";

    private static final int REQ_PICK_SCRIPT = 4001;

    private static final String[] MODES = {
            GadgetHelper.MODE_LISTEN, GadgetHelper.MODE_SCRIPT, GadgetHelper.MODE_CONNECT};
    private static final String[] MODE_LABELS = {
            "监听连接", "自动加载脚本", "连接远端",
    };
    private static final String[] CONFLICTS = {"pick-next", "fail"};
    private static final String[] CONFLICT_LABELS = {
            "自动选择下一个端口", "停止并报告失败"};

    private static final String[] SCRIPT_MODES = {
            Prefs.MODE_PLAIN, Prefs.MODE_DECRYPT_DEVICE, Prefs.MODE_DECRYPT_LOCAL,
            Prefs.MODE_ENCRYPT_DEVICE};
    private static final String[] SCRIPT_MODE_LABELS = {
            "明文直接推送", "密文在设备内解密", "密文解密后推送明文", "明文加密后推送密文",
    };

    private Prefs prefs;

    private CompoundButton enabledSwitch;
    private Spinner modeSpinner;
    private Spinner conflictSpinner;
    private EditText addressInput;
    private EditText portInput;
    private EditText scriptPathInput;
    private EditText rawJsonInput;
    private CompoundButton onLoadWaitSwitch;
    private CompoundButton reloadSwitch;
    private Spinner scriptModeSpinner;
    private EditText encKeyInput;
    private String scriptPlan = "";
    private MaterialButtonToggleGroup editorMode;
    private boolean switchingEditor;
    private String lastSavedJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_SCRIPT_ID);
        prefs = Prefs.forScript(this, id);
        if (!new ScriptStore(this).contains(prefs.profileId())) { finish(); return; }
        getIntent().putExtra(EXTRA_DETAIL, true);
        setContentView(R.layout.activity_gadget_config);
        setTitle(new ScriptStore(this).name(prefs.profileId()));

        enabledSwitch = findViewById(R.id.enabledSwitch);
        modeSpinner = findViewById(R.id.modeSpinner);
        conflictSpinner = findViewById(R.id.conflictSpinner);
        addressInput = findViewById(R.id.addressInput);
        portInput = findViewById(R.id.portInput);
        scriptPathInput = findViewById(R.id.scriptPathInput);
        rawJsonInput = findViewById(R.id.rawJsonInput);
        onLoadWaitSwitch = findViewById(R.id.onLoadWaitSwitch);
        reloadSwitch = findViewById(R.id.reloadSwitch);
        encKeyInput = findViewById(R.id.encKeyInput);
        scriptModeSpinner = findViewById(R.id.scriptModeSpinner);
        editorMode = findViewById(R.id.editorMode);
        Ui.bindHelp(this, R.id.helpInteraction, R.string.section_connection, R.string.help_interaction);
        Ui.bindHelp(this, R.id.helpCrypto, R.string.section_script_crypto, R.string.help_crypto);
        findViewById(R.id.helpScriptDetails).setOnClickListener(v -> Ui.help(this,
                getString(R.string.action_script_details), scriptPlan + (prefs.profileId().isEmpty()
                        ? "\n\n配置文件\n" + prefs.gadgetConfigPath() : "\n\n推送时为绑定的应用分别生成 Gadget 配置。")));

        ArrayAdapter<String> modes = new ArrayAdapter<>(this,
                R.layout.item_spinner, MODE_LABELS);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modes);

        ArrayAdapter<String> conflicts = new ArrayAdapter<>(this,
                R.layout.item_spinner, CONFLICT_LABELS);
        conflicts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        conflictSpinner.setAdapter(conflicts);

        enabledSwitch.setChecked(prefs.gadgetConfigEnabled());
        rawJsonInput.setText(GadgetHelper.pretty(prefs.gadgetConfigJson()));
        fillForm(prefs.gadgetConfigJson());
        rawJsonInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                updateScriptVisibility();
            }
        });
        lastSavedJson = prefs.gadgetConfigJson();
        editorMode.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked || switchingEditor) {
                return;
            }
            boolean raw = checkedId == R.id.jsonModeButton;
            if (raw) {
                rawJsonInput.setText(buildJson());
            } else if (!canUseForm(rawJsonInput.getText().toString())) {
                switchingEditor = true;
                group.check(R.id.jsonModeButton);
                switchingEditor = false;
                Ui.alert(this, "无法转换为表单", "请检查 JSON 的 interaction.type；表单支持 listen、script 和 connect。原始内容已保留。");
                return;
            } else {
                fillForm(rawJsonInput.getText().toString());
            }
            showEditor(raw);
        });
        if (!canUseForm(lastSavedJson)) {
            switchingEditor = true;
            editorMode.check(R.id.jsonModeButton);
            switchingEditor = false;
            showEditor(true);
        }

        modeSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                               int position, long id) {
                        updateFieldState();
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
        updateFieldState();

        // ---- script handling mode
        ArrayAdapter<String> scriptModes = new ArrayAdapter<>(this,
                R.layout.item_spinner, SCRIPT_MODE_LABELS);
        scriptModes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scriptModeSpinner.setAdapter(scriptModes);
        scriptModeSpinner.setSelection(indexOf(SCRIPT_MODES, prefs.scriptMode()));
        scriptModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateScriptPlan();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        encKeyInput.setText(prefs.scriptKeyHidden() ? "" : prefs.scriptKey());
        updateScriptPlan();

        encKeyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateScriptPlan();
            }
        });

        findViewById(R.id.generateButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rawJsonInput.setText(buildJson());
                Ui.toastShort(GadgetConfigActivity.this, "已由表单生成 JSON");
            }
        });
        findViewById(R.id.parseButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String json = rawJsonInput.getText().toString();
                if (!GadgetHelper.isValidJson(json)) {
                    Ui.toast(GadgetConfigActivity.this, "JSON 不合法，无法反填表单");
                    return;
                }
                fillForm(json);
                editorMode.check(R.id.formModeButton);
                Ui.toastShort(GadgetConfigActivity.this, "已由 JSON 反填表单");
            }
        });
        findViewById(R.id.pickScriptButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickScript();
            }
        });
        findViewById(R.id.writeNowButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                writeNow();
            }
        });
        findViewById(R.id.saveGadgetButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    private void showEditor(boolean raw) {
        findViewById(R.id.formFields).setVisibility(raw ? View.GONE : View.VISIBLE);
        findViewById(R.id.rawFields).setVisibility(raw ? View.VISIBLE : View.GONE);
        updateScriptVisibility();
    }

    private void updateScriptVisibility() {
        boolean scriptMode = modeSpinner.getSelectedItemPosition() == 1;
        if (editorMode.getCheckedButtonId() == R.id.jsonModeButton) {
            scriptMode = false;
            try {
                JSONObject interaction = new JSONObject(rawJsonInput.getText().toString())
                        .optJSONObject("interaction");
                scriptMode = interaction != null
                        && GadgetHelper.MODE_SCRIPT.equals(interaction.optString("type"));
            } catch (org.json.JSONException ignored) {
            }
        }
        findViewById(R.id.scriptControls).setVisibility(scriptMode ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("raw_editor", editorMode.getCheckedButtonId() == R.id.jsonModeButton);
        state.putString("last_saved_json", lastSavedJson);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        // Restore both editors before allowing a mode change to convert their content.
        switchingEditor = true;
        super.onRestoreInstanceState(state);
        boolean raw = state.getBoolean("raw_editor", false);
        editorMode.check(raw ? R.id.jsonModeButton : R.id.formModeButton);
        switchingEditor = false;
        lastSavedJson = state.getString("last_saved_json", prefs.gadgetConfigJson());
        showEditor(raw);
        updateFieldState();
    }

    private static boolean canUseForm(String json) {
        try {
            JSONObject interaction = new JSONObject(json).optJSONObject("interaction");
            if (interaction == null) return false;
            String type = interaction.optString("type");
            for (String mode : MODES) {
                if (mode.equals(type)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rawJsonInput == null) return;
        if (!prefs.gadgetConfigJson().equals(lastSavedJson)) {
            lastSavedJson = prefs.gadgetConfigJson();
            rawJsonInput.setText(GadgetHelper.pretty(lastSavedJson));
            fillForm(lastSavedJson);
            enabledSwitch.setChecked(prefs.gadgetConfigEnabled());
        }
        scriptModeSpinner.setSelection(indexOf(SCRIPT_MODES, prefs.scriptMode()));
        encKeyInput.setText(prefs.scriptKeyHidden() ? "" : prefs.scriptKey());
        updateScriptPlan();
    }

    private void updateScriptPlan() {
        StringBuilder sb = new StringBuilder();
        boolean locked = prefs.scriptLockedBySubConfig();
        boolean keyHidden = prefs.scriptKeyHidden();
        String mode = SCRIPT_MODES[scriptModeSpinner.getSelectedItemPosition()];
        String key = keyHidden ? prefs.scriptKey() : encKeyInput.getText().toString().trim();
        boolean usesCrypto = !Prefs.MODE_PLAIN.equals(mode);
        sb.append("脚本处理: ").append(Scripts.describePlan(mode, prefs.scriptName()));
        if (usesCrypto) {
            if (keyHidden) {
                sb.append("\n密钥: 🔒 由子配置提供（不显示、不可修改）");
            } else {
                sb.append("\n密钥: ").append(key.isEmpty() ? "未设置" : "已设置");
                if (!ScriptCrypto.keyIsAscii(key)) {
                    sb.append("   [!] 含非 ASCII 字符，XOR 无法正确还原");
                }
            }
        }
        sb.append("\n当前脚本: ").append(prefs.scriptPath());
        if (locked) {
            sb.append("\n\n这些设置来自导入的子配置：处理方式、密钥与脚本已锁定。");
        }
        scriptPlan = sb.toString();
        ((TextView) findViewById(R.id.scriptStateText)).setText(locked
                ? "来自子配置 · 设置已锁定" : (prefs.scriptName().isEmpty() ? "尚未选择脚本" : prefs.scriptName()));
        findViewById(R.id.keyField).setVisibility(usesCrypto && !keyHidden ? View.VISIBLE : View.GONE);
        applyScriptLock(locked, keyHidden);
    }

    /** Never render a key that came from a sub config, and honour the lock. */
    private void applyScriptLock(boolean locked, boolean keyHidden) {
        if (keyHidden && encKeyInput.length() > 0) {
            encKeyInput.setText("");
        }
        boolean keyEditable = !keyHidden && !locked;
        encKeyInput.setEnabled(keyEditable);
        scriptModeSpinner.setEnabled(!locked);
        findViewById(R.id.pickScriptButton).setEnabled(!locked);
        setEditable(findViewById(R.id.formFields), !locked);
        setEditable(findViewById(R.id.rawFields), !locked);
        setEditable(editorMode, !locked);
        enabledSwitch.setEnabled(!locked);
        findViewById(R.id.saveGadgetButton).setEnabled(!locked);
        findViewById(R.id.writeNowButton).setEnabled(!locked);
    }

    private static void setEditable(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEditable(group.getChildAt(i), enabled);
        }
    }

    private void updateFieldState() {
        updateScriptVisibility();
        boolean scriptMode = modeSpinner.getSelectedItemPosition() == 1;
        boolean connectMode = modeSpinner.getSelectedItemPosition() == 2;
        boolean listenMode = modeSpinner.getSelectedItemPosition() == 0;
        findViewById(R.id.networkFields).setVisibility(scriptMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.scriptFields).setVisibility(scriptMode ? View.VISIBLE : View.GONE);
        findViewById(R.id.conflictFields).setVisibility(listenMode ? View.VISIBLE : View.GONE);

        scriptPathInput.setEnabled(scriptMode);
        reloadSwitch.setEnabled(scriptMode);
        conflictSpinner.setEnabled(listenMode);
        addressInput.setEnabled(!scriptMode);
        portInput.setEnabled(!scriptMode);
        onLoadWaitSwitch.setEnabled(!scriptMode);
        if (connectMode) {
            onLoadWaitSwitch.setChecked(false);
        }
        if (prefs.scriptLockedBySubConfig()) setEditable(findViewById(R.id.formFields), false);
    }

    // ------------------------------------------------------------------ form

    private void fillForm(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject it = root.optJSONObject("interaction");
            if (it == null) {
                return;
            }
            String type = it.optString("type", GadgetHelper.MODE_LISTEN);
            modeSpinner.setSelection(indexOf(MODES, type));
            addressInput.setText(it.optString("address", "127.0.0.1"));
            portInput.setText(String.valueOf(it.optInt("port", 27042)));
            scriptPathInput.setText(it.optString("path", prefs.scriptPath()));
            onLoadWaitSwitch.setChecked("wait".equals(it.optString("on_load", "wait")));
            reloadSwitch.setChecked("reload".equals(it.optString("on_change", "")));
            conflictSpinner.setSelection(indexOf(CONFLICTS,
                    it.optString("on_port_conflict", "pick-next")));
        } catch (Exception e) {
            Ui.toast(this, "解析现有 gadget 配置失败: " + e.getMessage());
        }
        updateFieldState();
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private int port() {
        try {
            return Integer.parseInt(portInput.getText().toString().trim());
        } catch (Exception e) {
            return 27042;
        }
    }

    private String buildJson() {
        String address = addressInput.getText().toString().trim();
        String scriptPath = scriptPathInput.getText().toString().trim();
        int mode = modeSpinner.getSelectedItemPosition();
        if (mode == 1) {
            if (scriptPath.isEmpty()) {
                scriptPath = prefs.scriptPath();
            }
            return mergeForm(GadgetHelper.buildScriptConfig(scriptPath, false, reloadSwitch.isChecked()));
        }
        if (mode == 2) {
            return mergeForm(GadgetHelper.buildConnectConfig(address, port(), false));
        }
        return mergeForm(GadgetHelper.buildListenConfig(address, port(), onLoadWaitSwitch.isChecked(),
                CONFLICTS[conflictSpinner.getSelectedItemPosition()]));
    }

    private String mergeForm(String generated) {
        return GadgetHelper.mergeForm(rawJsonInput.getText().toString(), generated);
    }

    private String currentJson() {
        return editorMode.getCheckedButtonId() == R.id.jsonModeButton
                ? rawJsonInput.getText().toString() : buildJson();
    }

    // ------------------------------------------------------------------ actions

    private void save() {
        if (prefs.scriptLockedBySubConfig()) return;
        String json = currentJson();
        if (!GadgetHelper.isValidJson(json)) {
            Ui.alert(this, "JSON 不合法", "请修正后再保存。\n\n" + json);
            return;
        }
        if (!canCommitCryptoSettings()) return;
        commitCryptoSettings();
        prefs.setGadgetConfigJson(GadgetHelper.pretty(json));
        lastSavedJson = prefs.gadgetConfigJson();
        rawJsonInput.setText(lastSavedJson);
        prefs.setGadgetConfigEnabled(enabledSwitch.isChecked());
        saveScriptPath(json);
        Ui.toast(this, !prefs.profileId().isEmpty() ? "脚本设置已保存"
                : enabledSwitch.isChecked()
                ? "已保存，推送时会写入 " + prefs.gadgetConfigPath()
                : "已保存（未启用写入）");
        if (getIntent().getBooleanExtra(EXTRA_DETAIL, false)) {
            finish();
        }
    }

    private void writeNow() {
        if (prefs.scriptLockedBySubConfig()) return;
        String json = currentJson();
        if (!GadgetHelper.isValidJson(json)) {
            Ui.alert(this, "JSON 不合法", "请修正后再写入。");
            return;
        }
        if (!canCommitCryptoSettings()) return;
        commitCryptoSettings();
        prefs.setGadgetConfigJson(GadgetHelper.pretty(json));
        lastSavedJson = prefs.gadgetConfigJson();
        rawJsonInput.setText(lastSavedJson);
        saveScriptPath(json);
        prefs.setGadgetConfigEnabled(true);
        enabledSwitch.setChecked(true);

        final Dialog busy = Ui.busy(this, "正在写入设备…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final Pusher.Outcome outcome = Pusher.apply(GadgetConfigActivity.this);
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        Ui.report(GadgetConfigActivity.this,
                                outcome.ok ? "写入完成" : "写入未完全成功", outcome.text());
                    }
                });
            }
        });
    }

    private void saveScriptPath(String json) {
        try {
            JSONObject interaction = new JSONObject(json).optJSONObject("interaction");
            if (interaction != null && GadgetHelper.MODE_SCRIPT.equals(interaction.optString("type"))) {
                prefs.setScriptPath(interaction.optString("path", prefs.scriptPath()));
            }
        } catch (Exception ignored) {
        }
    }

    private void pickScript() {
        if (prefs.scriptLockedBySubConfig()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/javascript", "application/javascript", "application/x-javascript",
                "text/plain", "*/*"});
        try {
            startActivityForResult(intent, REQ_PICK_SCRIPT);
        } catch (Throwable t) {
            Ui.toast(this, "没有可用的文件选择器: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_SCRIPT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        final String json = currentJson();
        if (!GadgetHelper.isValidJson(json)) {
            Ui.alert(this, "JSON 不合法", "请修正配置后重新选择脚本。");
            return;
        }
        final ProfileSnapshot previous = new ProfileSnapshot(prefs);
        commitCryptoSettings();
        prefs.setGadgetConfigJson(GadgetHelper.pretty(json));
        prefs.setGadgetConfigEnabled(enabledSwitch.isChecked());
        final Dialog busy = Ui.busy(this, "正在推送脚本…");
        busy.show();
        Scripts.importScript(this, uri, prefs, true, new Scripts.Callback() {
            @Override
            public void onDone(String devicePath, String error, String note) {
                busy.dismiss();
                if (error != null) {
                    previous.restore(prefs);
                    Ui.report(GadgetConfigActivity.this, "脚本推送失败", error);
                    updateScriptPlan();
                    return;
                }
                scriptPathInput.setText(devicePath);
                modeSpinner.setSelection(1);
                rawJsonInput.setText(GadgetHelper.pretty(prefs.gadgetConfigJson()));
                lastSavedJson = prefs.gadgetConfigJson();
                enabledSwitch.setChecked(true);
                updateFieldState();
                updateScriptPlan();
                String text = "脚本已推送，gadget 将加载:\n" + devicePath;
                if (note != null && !note.isEmpty()) {
                    text += "\n\n" + note;
                }
                Ui.report(GadgetConfigActivity.this, "脚本就绪", text);
            }
        });
    }

    private boolean canCommitCryptoSettings() {
        if (prefs.scriptName().isEmpty()) return true;
        String mode = SCRIPT_MODES[scriptModeSpinner.getSelectedItemPosition()];
        String key = prefs.scriptKeyHidden() ? prefs.scriptKey()
                : encKeyInput.getText().toString().trim();
        if (mode.equals(prefs.scriptMode()) && key.equals(prefs.scriptKey())) return true;
        Ui.alert(this, "需要重新生成脚本",
                "脚本处理方式或密钥已经改变，但设备上的密文和 loader 仍使用旧参数。\n\n"
                        + "请点“选择脚本并推送”，重新选择源脚本后再保存。");
        return false;
    }

    private void commitCryptoSettings() {
        if (prefs.scriptLockedBySubConfig()) return;
        prefs.setScriptMode(SCRIPT_MODES[scriptModeSpinner.getSelectedItemPosition()]);
        if (!prefs.scriptKeyHidden()) prefs.setScriptKey(encKeyInput.getText().toString());
    }

    private static final class ProfileSnapshot {
        final String mode;
        final String key;
        final String json;
        final boolean enabled;
        final String path;
        final String name;
        final String cipherPath;

        ProfileSnapshot(Prefs prefs) {
            mode = prefs.scriptMode();
            key = prefs.scriptKey();
            json = prefs.gadgetConfigJson();
            enabled = prefs.gadgetConfigEnabled();
            path = prefs.scriptPath();
            name = prefs.scriptName();
            cipherPath = prefs.scriptCipherPath();
        }

        void restore(Prefs prefs) {
            prefs.setScriptMode(mode);
            prefs.setScriptKey(key);
            prefs.setGadgetConfigJson(json);
            prefs.setGadgetConfigEnabled(enabled);
            prefs.setScriptPath(path);
            prefs.setScriptName(name);
            prefs.setScriptCipherPath(cipherPath);
        }
    }
}
