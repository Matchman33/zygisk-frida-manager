package re.zyg.fri.manager;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.widget.TooltipCompat;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor for one entry of the `targets` array.
 *
 * Everything the module understands can be set here: the process name, the
 * start up delay (in seconds), the injected libraries and the child gating
 * configuration.
 */
public class TargetEditActivity extends BaseActivity {

    public static final String EXTRA_APP_NAME = "app_name";

    private static final int REQ_PICK_APP = 3001;

    private static final long[] DELAY_PRESETS = {0L, 10L, 30L, 60L, 90L, 120L};
    private static final String[] CHILD_MODES = {
            TargetConfig.MODE_FREEZE, TargetConfig.MODE_KILL, TargetConfig.MODE_INJECT};
    private static final String[] CHILD_MODE_LABELS = {
            "freeze — 冻结子进程", "kill — 杀死子进程", "inject — 注入子进程"};

    private Prefs prefs;
    private ConfigStore store;
    private TargetConfig target;
    private String originalName;
    private boolean isNew;

    private TextView appNameInput;
    private EditText delayInput;
    private TextView appLabelText;
    private TextView gadgetSummary;
    private String gadgetDetails = "";
    private CompoundButton enabledSwitch;
    private CompoundButton childGatingSwitch;
    private Spinner childModeSpinner;
    private LinearLayout libContainer;
    private LinearLayout childLibContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        store = ConfigStore.get(this);
        AppRepository.warmLabels(this);

        String appName = getIntent().getStringExtra(EXTRA_APP_NAME);
        TargetConfig existing = appName == null ? null : store.find(appName);
        isNew = existing == null;
        if (isNew) {
            target = new TargetConfig(appName == null ? "" : appName);
            String gadget = prefs.gadgetPath();
            if (!gadget.isEmpty()) {
                target.injectedLibraries.add(gadget);
            }
            target.startUpDelayMs = prefs.lastDelayMs();
            originalName = null;
        } else {
            target = existing.copy();
            originalName = existing.appName;
        }
        if (savedInstanceState != null) {
            target.scriptId = savedInstanceState.getString("selected_script", target.scriptId);
            if (!target.isLocked(TargetConfig.LOCK_APP)) {
                target.appName = savedInstanceState.getString("selected_app", target.appName);
            }
        }

        setContentView(R.layout.activity_target_edit);
        setTitle(isNew ? "新建目标" : "编辑目标");
        Ui.bindHelp(this, R.id.helpDelay, R.string.section_delay, R.string.help_delay);
        Ui.bindHelp(this, R.id.helpLibraries, R.string.section_libs, R.string.help_libraries);
        Ui.bindHelp(this, R.id.helpChildren, R.string.section_child, R.string.help_children);
        findViewById(R.id.helpGadget).setOnClickListener(v ->
                Ui.help(this, getString(R.string.section_gadget), gadgetDetails));

        appNameInput = findViewById(R.id.appNameInput);
        delayInput = findViewById(R.id.delayInput);
        appLabelText = findViewById(R.id.appLabelText);
        gadgetSummary = findViewById(R.id.gadgetSummary);
        enabledSwitch = findViewById(R.id.enabledSwitch);
        childGatingSwitch = findViewById(R.id.childGatingSwitch);
        childModeSpinner = findViewById(R.id.childModeSpinner);
        libContainer = findViewById(R.id.libContainer);
        childLibContainer = findViewById(R.id.childLibContainer);
        ChipGroup presets = findViewById(R.id.delayPresets);

        // ---- delay presets
        for (final long seconds : DELAY_PRESETS) {
            Chip b = new Chip(this);
            b.setText(String.valueOf(seconds));
            b.setContentDescription(seconds + " 秒");
            b.setTextSize(12f);
            b.setTextAppearance(R.style.TextAppearance_Zfm_Helper);
            b.setChipMinHeight(Ui.dp(this, 30));
            b.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this, R.color.zfm_field)));
            b.setChipStrokeWidth(0f);
            b.setChipStartPadding(Ui.dp(this, 8));
            b.setChipEndPadding(Ui.dp(this, 8));
            b.setTextStartPadding(0);
            b.setTextEndPadding(0);
            b.setEnsureMinTouchTargetSize(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    delayInput.setText(String.valueOf(seconds));
                }
            });
            presets.addView(b);
        }

        // ---- child gating mode
        ArrayAdapter<String> modes = new ArrayAdapter<>(this,
                R.layout.item_spinner, CHILD_MODE_LABELS);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        childModeSpinner.setAdapter(modes);

        // ---- fill
        appNameInput.setText(target.appName);
        delayInput.setText(String.valueOf(target.delaySeconds()));
        enabledSwitch.setChecked(target.enabled);
        childGatingSwitch.setChecked(target.childGatingEnabled);
        findViewById(R.id.childFields).setVisibility(target.childGatingEnabled ? View.VISIBLE : View.GONE);
        childGatingSwitch.setOnCheckedChangeListener((button, checked) ->
                findViewById(R.id.childFields).setVisibility(checked ? View.VISIBLE : View.GONE));
        childModeSpinner.setSelection(indexOfMode(target.childGatingMode));
        for (String lib : target.injectedLibraries) {
            addLibRow(libContainer, lib);
        }
        for (String lib : target.childGatingLibraries) {
            addLibRow(childLibContainer, lib);
        }
        if (target.childGatingLibraries.isEmpty()) {
            addLibRow(childLibContainer, prefs.childGadgetPath());
        }
        updateLabelText();
        updateGadgetSummary();

        // ---- listeners
        findViewById(R.id.pickAppButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(TargetEditActivity.this,
                        AppPickerActivity.class), REQ_PICK_APP);
            }
        });
        findViewById(R.id.addLibButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLibRow(libContainer, "");
            }
        });
        findViewById(R.id.addGadgetButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLibRow(libContainer, prefs.gadgetPath());
            }
        });
        findViewById(R.id.addGadget32Button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLibRow(libContainer, prefs.gadget32Path());
            }
        });
        findViewById(R.id.addChildLibButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLibRow(childLibContainer, "");
            }
        });
        findViewById(R.id.addChildGadgetButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLibRow(childLibContainer, prefs.childGadgetPath());
            }
        });
        findViewById(R.id.editGadgetButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(TargetEditActivity.this, GadgetConfigActivity.class)
                        .putExtra(EXTRA_DETAIL, true)
                        .putExtra(GadgetConfigActivity.EXTRA_SCRIPT_ID, target.scriptId));
            }
        });
        findViewById(R.id.pushScriptButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickScript();
            }
        });
        findViewById(R.id.saveButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        });
        findViewById(R.id.savePushButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(true);
            }
        });
        applyLocks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGadgetSummary();
        updateLabelText();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("selected_script", target.scriptId);
        state.putString("selected_app", target.appName);
        super.onSaveInstanceState(state);
    }

    // ------------------------------------------------------------------ helpers

    private static int indexOfMode(String mode) {
        for (int i = 0; i < CHILD_MODES.length; i++) {
            if (CHILD_MODES[i].equals(mode)) {
                return i;
            }
        }
        return 0;
    }

    private void updateLabelText() {
        String pkg = target.appName;
        if (pkg.isEmpty()) {
            appLabelText.setText("");
            return;
        }
        String label = AppRepository.labelOf(this, pkg);
        if (label != null && !label.equals(pkg)) {
            appLabelText.setText(label + (target.isLocked(TargetConfig.LOCK_APP) ? " · 已锁定" : ""));
        } else if (pkg.contains(":")) {
            appLabelText.setText("子进程 / 自定义进程名");
        } else {
            appLabelText.setText("未安装的应用或自定义进程");
        }
    }

    private void updateGadgetSummary() {
        if (!new ScriptStore(this).contains(target.scriptId)) target.scriptId = "";
        Prefs prefs = Prefs.forScript(this, target.scriptId);
        String text = "当前 gadget 配置: " + GadgetHelper.summarize(prefs.gadgetConfigJson())
                + "\n脚本处理: " + Scripts.describePlan(prefs)
                + "\n推送时" + (prefs.gadgetConfigEnabled()
                ? "会写入 " + prefs.gadgetConfigPath()
                : "不写入 gadget 配置（目标只会加载裸 gadget，需要自己连上去）");
        gadgetDetails = text;
        String mode = "未配置";
        try {
            org.json.JSONObject interaction = new org.json.JSONObject(prefs.gadgetConfigJson()).optJSONObject("interaction");
            if (interaction != null) {
                String type = interaction.optString("type");
                mode = "script".equals(type) ? "自动脚本" : "listen".equals(type) ? "监听连接" : "connect".equals(type) ? "远程连接" : type;
            }
        } catch (Exception ignored) {
        }
        gadgetSummary.setText(new ScriptStore(this).name(target.scriptId) + " · " + mode
                + (target.isLocked(TargetConfig.LOCK_SCRIPT) ? " · 已锁定" : ""));
    }

    private void addLibRow(final LinearLayout container, String path) {
        final View row = getLayoutInflater().inflate(R.layout.item_lib, container, false);
        EditText input = row.findViewById(R.id.libPath);
        input.setText(path == null ? "" : path);
        TooltipCompat.setTooltipText(row.findViewById(R.id.removeLib), getString(R.string.remove_library));
        row.findViewById(R.id.removeLib).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                container.removeView(row);
            }
        });
        container.addView(row);
    }

    private static List<String> collectLibs(LinearLayout container) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View row = container.getChildAt(i);
            EditText input = row.findViewById(R.id.libPath);
            if (input == null) {
                continue;
            }
            String value = input.getText().toString().trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ save

    private boolean collect() {
        if (!new ScriptStore(this).contains(target.scriptId)) {
            target.scriptId = "";
        }
        String name = target.appName;
        if (name.isEmpty()) {
            Ui.toast(this, "请先选择应用");
            return false;
        }
        if (name.contains(" ")) {
            Ui.toast(this, "包名里不能有空格");
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(delayInput.getText().toString().trim());
        } catch (Exception e) {
            seconds = 0L;
        }
        if (seconds < 0) {
            seconds = 0;
        }
        // locked fields always keep the imported value, whatever the widgets say
        if (target.isLocked(TargetConfig.LOCK_APP)) {
            name = target.appName;
        }
        if (target.isLocked(TargetConfig.LOCK_DELAY)) {
            seconds = target.delaySeconds();
        }
        target.appName = name;
        target.setDelaySeconds(seconds);
        target.enabled = enabledSwitch.isChecked();
        if (!target.isLocked(TargetConfig.LOCK_LIBRARIES)) {
            target.injectedLibraries.clear();
            target.injectedLibraries.addAll(collectLibs(libContainer));
        }
        target.childGatingEnabled = childGatingSwitch.isChecked();
        target.childGatingMode = CHILD_MODES[childModeSpinner.getSelectedItemPosition()];
        if (!target.isLocked(TargetConfig.LOCK_CHILD_GATING)) {
            target.childGatingLibraries.clear();
            target.childGatingLibraries.addAll(collectLibs(childLibContainer));
        }
        return true;
    }

    /**
     * Fields that came from a sub config are shown read only, so the receiver
     * cannot change what the exporter locked.
     */
    private void applyLocks() {
        boolean any = false;
        boolean scriptLocked = target.isLocked(TargetConfig.LOCK_SCRIPT)
                || target.isLocked(TargetConfig.LOCK_LIBRARIES);
        findViewById(R.id.pushScriptButton).setEnabled(!scriptLocked);
        if (target.isLocked(TargetConfig.LOCK_APP)) {
            appNameInput.setEnabled(false);
            findViewById(R.id.pickAppButton).setEnabled(false);
            appLabelText.append("   🔒 锁定");
            any = true;
        }
        if (target.isLocked(TargetConfig.LOCK_DELAY)) {
            delayInput.setEnabled(false);
            ChipGroup presets = findViewById(R.id.delayPresets);
            for (int i = 0; i < presets.getChildCount(); i++) {
                presets.getChildAt(i).setEnabled(false);
            }
            any = true;
        }
        if (target.isLocked(TargetConfig.LOCK_LIBRARIES)) {
            setEnabledRecursive(libContainer, false);
            findViewById(R.id.addLibButton).setEnabled(false);
            findViewById(R.id.addGadgetButton).setEnabled(false);
            findViewById(R.id.addGadget32Button).setEnabled(false);
            any = true;
        }
        if (target.isLocked(TargetConfig.LOCK_CHILD_GATING)) {
            childGatingSwitch.setEnabled(false);
            childModeSpinner.setEnabled(false);
            setEnabledRecursive(childLibContainer, false);
            findViewById(R.id.addChildLibButton).setEnabled(false);
            findViewById(R.id.addChildGadgetButton).setEnabled(false);
            any = true;
        }
        if (any) {
            Ui.toastShort(this, "该目标来自子配置：🔒 字段已锁定，只有允许的项可改");
        }
    }

    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledRecursive(group.getChildAt(i), enabled);
            }
        }
    }

    private void save(final boolean push) {
        if (!collect()) {
            return;
        }
        if (target.injectedLibraries.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("没有配置任何库")
                    .setMessage("这个目标不会注入任何东西（连 gadget 也没有）。\n确定要保存吗？")
                    .setPositiveButton(R.string.action_save,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    persist(push);
                                }
                            })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        persist(push);
    }

    private void persist(boolean push) {
        if (originalName != null && !originalName.equals(target.appName)) {
            store.remove(originalName);
        }
        store.put(target);
        prefs.setLastDelayMs(target.startUpDelayMs);
        originalName = target.appName;

        if (!push) {
            Ui.toast(this, "已保存（记得点「推送并应用」）");
            finish();
            return;
        }

        final Dialog busy = Ui.busy(this, "正在写入设备…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final Pusher.Outcome outcome = Pusher.apply(TargetEditActivity.this);
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        // finish only after the user closed the report, otherwise
                        // the dialog dies together with the activity
                        Ui.report(TargetEditActivity.this,
                                outcome.ok ? "推送完成" : "推送未完全成功", outcome.text(),
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        finish();
                                    }
                                });
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ pickers

    private void pickScript() {
        if (target.isLocked(TargetConfig.LOCK_SCRIPT) || target.isLocked(TargetConfig.LOCK_LIBRARIES)) return;
        List<ScriptStore.Entry> entries = new ScriptStore(this).entries();
        String[] names = new String[entries.size()];
        int selected = -1;
        for (int i = 0; i < entries.size(); i++) {
            names[i] = entries.get(i).name;
            if (entries.get(i).id.equals(target.scriptId)) selected = i;
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.select_script)
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    target.scriptId = entries.get(which).id;
                    if (!target.scriptId.isEmpty()) ensureGadgetLib();
                    updateGadgetSummary();
                    dialog.dismiss();
                })
                .setNeutralButton("管理脚本", (dialog, which) -> startActivity(
                        new Intent(this, ScriptsActivity.class).putExtra(EXTRA_DETAIL, true)))
                .setNegativeButton(R.string.action_cancel, null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK_APP) {
            if (target.isLocked(TargetConfig.LOCK_APP)) return;
            String pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE);
            if (pkg != null && !pkg.isEmpty()) {
                target.appName = pkg;
                appNameInput.setText(pkg);
                updateLabelText();
            }
            return;
        }
    }

    /** Makes sure the gadget itself is in the injected library list. */
    private void ensureGadgetLib() {
        List<String> current = collectLibs(libContainer);
        for (String lib : current) {
            if (lib.equals(prefs.gadgetPath()) || lib.equals(prefs.gadget32Path())) {
                return;
            }
        }
        addLibRow(libContainer, prefs.gadgetPath());
    }
}
