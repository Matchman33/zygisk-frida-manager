package re.zyg.fri.manager;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButtonToggleGroup;
import java.util.ArrayList;
import java.util.List;

public class PatchSoActivity extends BaseActivity {
    private static final int PICK_SO = 6101, PICK_SCRIPT = 6102, EXPORT_ZIP = 6103;
    private PatchSoViewModel model;
    private PatchSoViewModel.State state;
    private Spinner presets;
    private MaterialButtonToggleGroup configMode;
    private EditText address, port, json;
    private final List<String> profileConfigs = new ArrayList<>();
    private boolean restoring;
    private boolean applyingPreset;
    private boolean portEdited;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patch_so);
        model = new ViewModelProvider(this).get(PatchSoViewModel.class);
        presets = findViewById(R.id.patchPreset);
        configMode = findViewById(R.id.patchConfigMode);
        address = findViewById(R.id.patchAddress);
        port = findViewById(R.id.patchPort);
        json = findViewById(R.id.patchJson);
        portEdited = savedInstanceState != null && savedInstanceState.getBoolean("patch_port_edited", false);
        List<String> labels = new ArrayList<>();
        java.util.Collections.addAll(labels, "监听并继续", "监听并等待连接", "自动加载脚本", "连接远端 Portal");
        for (ScriptStore.Entry entry : new ScriptStore(this).entries()) {
            labels.add("已有预设 · " + entry.name);
            profileConfigs.add(Prefs.forScript(this, entry.id).gadgetConfigJson());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presets.setAdapter(adapter);
        address.setText("127.0.0.1");
        port.setText("27042");
        try { json.setText(SoPatchConfig.preset(0, "127.0.0.1", 27042)); } catch (Exception ignored) {}
        presets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!restoring && !portEdited && (position == 0 || position == 1 || position == 3)) {
                    applyingPreset = true;
                    port.setText(position == 3 ? "27052" : "27042");
                    applyingPreset = false;
                }
                updateEditor();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        configMode.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            if (!restoring && checkedId == R.id.patchCustomMode) {
                try { json.setText(presetJson()); } catch (Exception ignored) {}
            }
            updateEditor();
        });
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateResult(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        address.addTextChangedListener(watcher);
        port.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!restoring && !applyingPreset) portEdited = true;
                updateResult();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        json.addTextChangedListener(watcher);
        findViewById(R.id.patchPickSo).setOnClickListener(v -> pick(PICK_SO));
        findViewById(R.id.patchPickScript).setOnClickListener(v -> pick(PICK_SCRIPT));
        findViewById(R.id.patchRemoveScript).setOnClickListener(v -> model.removeScript());
        findViewById(R.id.patchInputHelp).setOnClickListener(v -> {
            if (state == null || state.info == null) {
                Ui.help(this, "目标 SO", getString(R.string.help_patch_so));
            } else {
                Ui.help(this, state.inputName, "架构：" + state.info.abi + "\n\n原始依赖\n"
                        + (state.info.needed.isEmpty() ? "无 DT_NEEDED" : android.text.TextUtils.join("\n", state.info.needed)));
            }
        });
        Ui.bindHelp(this, R.id.patchConfigHelp, R.string.patch_configuration, R.string.help_patch_config);
        findViewById(R.id.patchBuild).setOnClickListener(v -> {
            try { model.build(currentConfig()); }
            catch (Exception failure) { showError(failure.getMessage()); }
        });
        findViewById(R.id.patchExport).setOnClickListener(v -> {
            if (state == null || state.result == null) return;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, state.result.filename);
            try { startActivityForResult(intent, EXPORT_ZIP); }
            catch (Exception failure) { showError("没有可用的文件保存器"); }
        });
        model.state.observe(this, value -> { state = value; render(); });
        updateEditor();
    }

    private String presetJson() throws Exception {
        int selected = presets.getSelectedItemPosition();
        if (selected >= 4) return profileConfigs.get(selected - 4);
        int number = 27042;
        if (selected != 2) {
            try { number = Integer.parseInt(port.getText().toString().trim()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("端口必须是 1 到 65535 的整数"); }
        }
        return SoPatchConfig.preset(selected, address.getText().toString().trim(), number);
    }

    private String currentConfig() throws Exception {
        String text = configMode.getCheckedButtonId() == R.id.patchCustomMode ? json.getText().toString() : presetJson();
        return SoPatchConfig.validate(text, state != null && !state.scriptName.isEmpty());
    }

    private void updateEditor() {
        boolean custom = configMode.getCheckedButtonId() == R.id.patchCustomMode;
        findViewById(R.id.patchPresetFields).setVisibility(custom ? View.GONE : View.VISIBLE);
        findViewById(R.id.patchJsonField).setVisibility(custom ? View.VISIBLE : View.GONE);
        int selection = presets.getSelectedItemPosition();
        findViewById(R.id.patchNetworkFields).setVisibility(selection < 4 && selection != 2 ? View.VISIBLE : View.GONE);
        updateResult();
    }

    private void render() {
        if (state == null) return;
        ((TextView) findViewById(R.id.patchGadgetVersion)).setText("Frida Gadget " + state.version);
        ((TextView) findViewById(R.id.patchInputName)).setText(state.inputName.isEmpty() ? "尚未选择 SO 文件" : state.inputName);
        ((TextView) findViewById(R.id.patchInputInfo)).setText(state.info == null ? "" :
                state.info.abi + " · " + state.info.needed.size() + " 项依赖");
        ((TextView) findViewById(R.id.patchScriptName)).setText(state.scriptName.isEmpty() ? "未附带脚本" : state.scriptName);
        findViewById(R.id.patchRemoveScript).setVisibility(state.scriptName.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.patchProgressText)).setText(state.progress);
        findViewById(R.id.patchProgress).setVisibility(state.busy ? View.VISIBLE : View.GONE);
        setEnabled(findViewById(R.id.patchForm), !state.busy);
        findViewById(R.id.patchBuild).setEnabled(!state.busy && state.info != null);
        showError(state.error);
        updateResult();
    }

    private void updateResult() {
        if (state == null) return;
        boolean valid = false;
        if (!state.busy && state.result != null) {
            try { valid = currentConfig().equals(state.result.config); } catch (Exception ignored) {}
        }
        findViewById(R.id.patchExport).setEnabled(valid);
        ((TextView) findViewById(R.id.patchResult)).setText(valid ? state.result.summary : "");
        findViewById(R.id.patchResult).setVisibility(valid ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        TextView error = findViewById(R.id.patchError);
        error.setText(message == null ? "操作失败" : message);
        error.setVisibility(message != null && message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }

    private void pick(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(intent, request); }
        catch (Exception failure) { showError("没有可用的文件选择器"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == PICK_SO) model.importSo(uri);
        else if (requestCode == PICK_SCRIPT) model.importScript(uri);
        else if (requestCode == EXPORT_ZIP) {
            if (state == null || state.result == null) showError("待导出的补丁已失效，请重新生成后导出");
            else model.export(uri);
        }
    }

    @Override protected void onRestoreInstanceState(Bundle savedInstanceState) {
        restoring = true;
        super.onRestoreInstanceState(savedInstanceState);
        restoring = false;
        updateEditor();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("patch_port_edited", portEdited);
        super.onSaveInstanceState(state);
    }
}
