package re.zyg.fri.manager;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Target list: pick an app, set the delay, push the config. */
public class MainActivity extends BaseActivity {

    private static final int REQ_PICK_APP = 1001;
    private static final int REQ_EXPORT_SUBCONFIG = 1002;

    private Prefs prefs;
    private ConfigStore store;
    private RecyclerView listView;
    private TextView statusText;
    private View emptyText;
    private TargetAdapter adapter;
    private RuntimeStatus.Snapshot runtimeStatus =
            new RuntimeStatus.Snapshot(false, Collections.emptyMap());
    private DeploymentState.Status deploymentState = DeploymentState.Status.NEVER_PUSHED;
    private String environmentDetails = "正在检测设备环境";

    private String pendingExportText;
    private String pendingExportName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.rememberContext(this);
        setContentView(R.layout.activity_main);
        setTitle("ZygiskFrida");

        prefs = Prefs.get(this);
        store = ConfigStore.get(this);
        AppRepository.warmLabels(this);

        statusText = findViewById(R.id.statusText);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(findViewById(R.id.buttonAdd), getString(R.string.action_add_target));
        findViewById(R.id.helpEnvironment).setOnClickListener(v ->
                Ui.help(this, "设备环境", environmentDetails + "\n\n" + getString(R.string.help_environment)));
        emptyText = findViewById(R.id.emptyText);
        listView = findViewById(R.id.targetList);
        adapter = new TargetAdapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);

        findViewById(R.id.buttonAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, AppPickerActivity.class),
                        REQ_PICK_APP);
            }
        });
        findViewById(R.id.buttonPush).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pushConfig();
            }
        });
        statusText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ToolsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        refreshStatus();
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_pull) {
            pullFromDevice();
            return true;
        }
        if (id == R.id.menu_about) {
            Ui.alert(this, getString(R.string.title_about), getString(R.string.about_text));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ list

    private void refreshList() {
        adapter.reload();
        ((TextView) findViewById(R.id.targetCount)).setText(String.valueOf(adapter.getItemCount()));
        emptyText.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        TextView deployment = findViewById(R.id.deploymentStatus);
        deploymentState = DeploymentState.status(this);
        deployment.setText(deploymentLabel(deploymentState));
        deployment.setTextColor(ContextCompat.getColor(this,
                deploymentState == DeploymentState.Status.CURRENT
                        ? R.color.zfm_success : R.color.zfm_warning));
    }

    private static String deploymentLabel(DeploymentState.Status state) {
        if (state == DeploymentState.Status.CURRENT) return "本机配置与上次推送一致";
        if (state == DeploymentState.Status.DIRTY) return "本机配置有改动，等待推送";
        return "本机配置尚未推送";
    }

    private void refreshStatus() {
        statusText.setText(R.string.status_checking);
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final String status = DeviceEnv.quickStatus(MainActivity.this);
                final RuntimeStatus.Snapshot processes = RuntimeStatus.inspect(
                        MainActivity.this, new ArrayList<>(store.targets()));
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        runtimeStatus = processes;
                        adapter.notifyDataSetChanged();
                        environmentDetails = formatQuickStatus(status);
                        int ready = 0;
                        for (String key : new String[]{"ROOT=", "MODDIR=", "GADGET=", "CONFIG="}) {
                            if (stateLabel(status == null ? "" : status, key, "").endsWith("正常")) ready++;
                        }
                        statusText.setText(ready == 4 ? "设备已就绪" : "环境需要检查");
                        ((TextView) findViewById(R.id.statusCaption)).setText(ready + " / 4 项检测通过");
                        ((ImageView) findViewById(R.id.statusIcon)).setColorFilter(ContextCompat.getColor(
                                MainActivity.this, ready == 4 ? R.color.zfm_success : R.color.zfm_warning));
                    }
                });
            }
        });
    }

    private String formatQuickStatus(String status) {
        if (status == null || status.startsWith("状态检测失败")) {
            return status == null ? "设备状态不可用" : status;
        }
        return "设备环境\n"
                + stateLabel(status, "ROOT=", "Root") + "   "
                + stateLabel(status, "MODDIR=", "模块") + "\n"
                + stateLabel(status, "GADGET=", "Gadget") + "   "
                + stateLabel(status, "CONFIG=", "配置");
    }

    private static String stateLabel(String status, String key, String label) {
        int start = status.indexOf(key);
        if (start < 0) {
            return label + " 未知";
        }
        start += key.length();
        int end = status.indexOf(' ', start);
        String value = end < 0 ? status.substring(start) : status.substring(start, end);
        if ("ok".equals(value)) {
            return label + " 正常";
        }
        if ("missing".equals(value)) {
            return label + " 未就绪";
        }
        return label + " 异常";
    }

    private void editTarget(String appName) {
        Intent i = new Intent(this, TargetEditActivity.class);
        i.putExtra(TargetEditActivity.EXTRA_APP_NAME, appName);
        startActivity(i);
    }

    private void showItemMenu(final TargetConfig target) {
        final String[] actions = new String[]{
                getString(R.string.action_edit),
                target.enabled ? getString(R.string.action_disable) : getString(R.string.action_enable),
                getString(R.string.action_launch),
                getString(R.string.action_stop_app),
                getString(R.string.action_force_stop),
                getString(R.string.action_export_subconfig),
                getString(R.string.action_delete),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(target.appName)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                editTarget(target.appName);
                                break;
                            case 1:
                                target.enabled = !target.enabled;
                                store.put(target);
                                refreshList();
                                break;
                            case 2:
                                launchApp(target.appName);
                                break;
                            case 3:
                                stopApp(target.appName, false);
                                break;
                            case 4:
                                stopApp(target.appName, true);
                                break;
                            case 5:
                                exportSubConfig(target);
                                break;
                            case 6:
                                confirmDelete(target);
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void confirmDelete(final TargetConfig target) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage("删除目标 " + target.appName + " ？\n（还需要点「推送并应用」才会从设备上的 config.json 里移除）")
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.remove(target.appName);
                        refreshList();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ sub config

    /**
     * Exports one app as a sub config: everything the recipient needs, with the
     * key sealed inside and most fields locked.
     */
    private void exportSubConfig(final TargetConfig target) {
        Prefs prefs = Prefs.forScript(this, target.scriptId);
        final String cipher = resolveCipherPath(prefs);
        if (Prefs.MODE_PLAIN.equals(prefs.scriptMode()) || cipher.isEmpty()) {
            String extra = "";
            if (!cipher.isEmpty()) {
                extra = "\n\n设备上其实已经有密文：\n" + cipher
                        + "\n只要把「脚本处理方式」重新选一次（例如「明文 → 管理器加密」），"
                        + "再选一次脚本让它记录下密文路径，就能导出。";
            }
            Ui.alert(this, "导出子配置",
                    "当前没有可保护的密文。\n\n子配置里内置的是加密后的脚本，所以请先把"
                            + "「Gadget / 脚本配置 → 脚本处理方式」改成\n"
                            + "· 明文 → 管理器加密，或\n· 密文 → 设备内解密\n"
                            + "然后重新选一次脚本，再导出。" + extra);
            return;
        }

        final android.widget.CheckBox allowDelay = new android.widget.CheckBox(this);
        allowDelay.setText("允许对方修改注入延时");
        final android.widget.CheckBox allowEnabled = new android.widget.CheckBox(this);
        allowEnabled.setText("允许对方启用 / 停用这个目标");
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(allowDelay);
        box.addView(allowEnabled);
        android.widget.TextView note = new android.widget.TextView(this);
        note.setTextSize(12f);
        note.setText("其余内容一律锁定：目标应用、注入的库、脚本与加密方式、Gadget 配置。\n"
                + "密钥会密封在文件里，导入方看不到、也改不了。");
        box.addView(note);

        new MaterialAlertDialogBuilder(this)
                .setTitle("导出子配置 · " + target.appName)
                .setView(box)
                .setPositiveButton("选择保存位置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prepareExport(target, allowDelay.isChecked(), allowEnabled.isChecked());
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Device path of the encrypted payload.
     *
     * Older builds did not record it, so fall back to deriving it from the loader
     * path that is stored ({@code <base>.loader.js} next to {@code <base>.so}).
     */
    private String resolveCipherPath(Prefs prefs) {
        String recorded = prefs.scriptCipherPath();
        if (!recorded.isEmpty()) {
            return recorded;
        }
        String loader = prefs.scriptPath();
        if (loader == null || !loader.endsWith(".loader.js")) {
            return "";
        }
        String base = loader.substring(0, loader.length() - ".loader.js".length());
        String[] candidates = {base + ".so", base + ".enc", base};
        for (String candidate : candidates) {
            if (Shell.exists(this, candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private void prepareExport(final TargetConfig target, final boolean allowDelay,
                               final boolean allowEnabled) {
        final Prefs prefs = Prefs.forScript(this, target.scriptId);
        final Dialog busy = Ui.busy(this, "正在打包子配置…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                String text = null;
                String error = null;
                final String cipherPath = resolveCipherPath(prefs);
                try {
                    String cipherText = Shell.readTextFile(MainActivity.this, cipherPath);
                    if (cipherText == null || cipherText.trim().isEmpty()) {
                        error = "读不到密文: " + cipherPath + "（root 权限？文件被删了？）";
                    } else {
                        String childJson = "";
                        if (target.childGatingEnabled) {
                            org.json.JSONObject cg = target.toJson()
                                    .optJSONObject("child_gating");
                            childJson = cg == null ? "" : cg.toString();
                        }
                        text = SubConfig.export(target.appName,
                                AppRepository.labelOf(MainActivity.this, target.appName),
                                target.startUpDelayMs, target.enabled,
                                target.injectedLibraries, childJson,
                                prefs.scriptMode(), prefs.scriptName(),
                                new java.io.File(cipherPath).getName(),
                                cipherText.trim(), SubConfig.sha256HexOfText(cipherText.trim()),
                                prefs.gadgetConfigJson(), prefs.scriptKey(),
                                allowEnabled, allowDelay);
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String out = text;
                final String err = error;
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (err != null || out == null) {
                            Ui.report(MainActivity.this, "导出失败", err == null ? "未知错误" : err);
                            return;
                        }
                        pendingExportText = out;
                        pendingExportName = SubConfig.suggestFileName(target.appName,
                                AppRepository.labelOf(MainActivity.this, target.appName));
                        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        create.addCategory(Intent.CATEGORY_OPENABLE);
                        create.setType("application/octet-stream");
                        create.putExtra(Intent.EXTRA_TITLE, pendingExportName);
                        try {
                            startActivityForResult(create, REQ_EXPORT_SUBCONFIG);
                        } catch (Throwable t) {
                            Ui.report(MainActivity.this, "导出失败",
                                    "没有可用的保存选择器: " + t);
                        }
                    }
                });
            }
        });
    }

    private void writeExport(Uri uri) {
        try {
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                Ui.report(this, "导出失败", "无法写入所选位置");
                return;
            }
            byte[] bytes = pendingExportText.getBytes(Shell.UTF8);
            try {
                os.write(bytes);
            } finally {
                os.close();
            }
            Ui.report(this, "导出完成",
                    "已写出 " + pendingExportName + "（" + bytes.length + " 字节）\n"
                            + "位置: " + uri + "\n\n"
                            + "对方用同一个管理器「工具与环境 → 导入子配置」即可；\n"
                            + "密钥已密封在文件里，导入方看不到也改不了。");
        } catch (Exception e) {
            Ui.report(this, "导出失败", e.toString());
        }
    }

    private void launchApp(String packageName) {
        packageName = AppControl.packageName(packageName);
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Ui.toast(this, "找不到 " + packageName + " 的启动入口");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void stopApp(final String packageName, final boolean restart) {
        final Dialog busy = Ui.busy(this, "正在停止应用…");
        busy.show();
        final int userId = android.os.Process.myUid() / 100000;
        Bg.run(new Runnable() {
            @Override
            public void run() {
                Shell.Result stopped = AppControl.stop(packageName, userId,
                        command -> Shell.su(MainActivity.this, command));
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (!stopped.ok()) {
                            Ui.report(MainActivity.this, "停止应用失败", stopped.dump());
                        } else if (restart) {
                            launchApp(packageName);
                            Bg.ui(() -> refreshStatus(), 1000L);
                        } else {
                            Ui.toastShort(MainActivity.this, "应用已停止");
                            refreshStatus();
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_SUBCONFIG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingExportText != null) {
                writeExport(data.getData());
            }
            return;
        }
        if (requestCode == REQ_PICK_APP && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE);
            if (pkg == null || pkg.isEmpty()) {
                return;
            }
            String label = data.getStringExtra(AppPickerActivity.EXTRA_LABEL);
            if (store.find(pkg) == null) {
                TargetConfig t = new TargetConfig(pkg);
                t.appLabel = label == null ? "" : label;
                if (!prefs.gadgetPath().isEmpty()) {
                    t.injectedLibraries.add(prefs.gadgetPath());
                }
                t.startUpDelayMs = prefs.lastDelayMs();
                store.put(t);
            }
            refreshList();
            editTarget(pkg);
        }
    }

    // ------------------------------------------------------------------ push

    private void pushConfig() {
        if (store.targets().isEmpty()) {
            // Pushing an empty list is the only way to undo a config from the
            // app, so do not just refuse - ask explicitly instead.
            new MaterialAlertDialogBuilder(this)
                    .setTitle("本机没有目标")
                    .setMessage("推送会把设备上的 config.json 写成空列表，"
                            + "所有目标立即停止注入（相当于停用整个模块）。\n\n要继续吗？")
                    .setPositiveButton("清空设备配置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            doPush();
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }
        final Dialog busy = Ui.busy(this, "读取设备上的 config.json…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final String deviceJson = Shell.readTextFile(MainActivity.this, prefs.configPath());
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        showPushConfirm(deviceJson);
                    }
                });
            }
        });
    }

    private List<String> deviceOnlyTargets(String deviceJson) {
        List<String> out = new ArrayList<>();
        if (deviceJson == null || deviceJson.trim().isEmpty()) {
            return out;
        }
        try {
            JSONObject root = new JSONObject(deviceJson);
            JSONArray targets = root.optJSONArray("targets");
            if (targets == null) {
                return out;
            }
            for (int i = 0; i < targets.length(); i++) {
                JSONObject o = targets.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("app_name", "");
                if (!name.isEmpty() && store.find(name) == null) {
                    out.add(name);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void showPushConfirm(String deviceJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("将写入 ").append(store.targets().size()).append(" 个目标到:\n")
                .append(prefs.configPath()).append("\n\n");
        for (TargetConfig t : store.targets()) {
            sb.append(t.enabled ? "[启用] " : "[停用] ").append(t.appName)
                    .append("   延时 ").append(t.delaySeconds()).append("s\n");
        }
        if (prefs.gadgetConfigEnabled()) {
            sb.append("\n同时写入 gadget 配置:\n").append(prefs.gadgetConfigPath()).append('\n');
        }
        List<String> deviceOnly = deviceOnlyTargets(deviceJson);
        if (!deviceOnly.isEmpty()) {
            sb.append("\n[!] 设备上还有本机不管理的目标，推送后会丢失:\n");
            for (String name : deviceOnly) {
                sb.append("    ").append(name).append('\n');
            }
            sb.append("可先用菜单里的「从设备导入」合并。\n");
        } else if (deviceJson == null) {
            sb.append("\n（设备上还没有 config.json，将新建）\n");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_push_apply)
                .setMessage(sb.toString())
                .setPositiveButton("推送", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doPush();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doPush() {
        final Dialog busy = Ui.busy(this, "正在写入设备…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final Pusher.Outcome outcome = Pusher.apply(MainActivity.this);
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        Ui.report(MainActivity.this,
                                outcome.ok ? "推送完成" : "推送未完全成功",
                                outcome.text());
                        refreshList();
                        refreshStatus();
                    }
                });
            }
        });
    }

    private void pullFromDevice() {
        final Dialog busy = Ui.busy(this, "读取设备上的 config.json…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                final String json = Shell.readTextFile(MainActivity.this, prefs.configPath());
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (json == null || json.trim().isEmpty()) {
                            Ui.alert(MainActivity.this, getString(R.string.action_pull),
                                    "读不到 " + prefs.configPath() + "\n（文件不存在或没有 root 权限）");
                            return;
                        }
                        new MaterialAlertDialogBuilder(MainActivity.this)
                                .setTitle(R.string.action_pull)
                                .setMessage("设备上的 config.json:\n\n" + json)
                                .setPositiveButton("合并到本机", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        String error = store.mergeFromJson(json, false);
                                        if (error != null) {
                                            Ui.alert(MainActivity.this, "导入失败", error);
                                        }
                                        refreshList();
                                    }
                                })
                                .setNeutralButton("替换本机", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        String error = store.mergeFromJson(json, true);
                                        if (error != null) {
                                            Ui.alert(MainActivity.this, "导入失败", error);
                                        }
                                        refreshList();
                                    }
                                })
                                .setNegativeButton(R.string.action_cancel, null)
                                .show();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ adapter

    private final class TargetAdapter extends RecyclerView.Adapter<TargetViewHolder> {

        private final List<TargetConfig> items = new ArrayList<>();

        void reload() {
            items.clear();
            items.addAll(store.targets());
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        public TargetConfig getItem(int position) {
            return items.get(position);
        }

        @Override
        public TargetViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_target, parent, false);
            return new TargetViewHolder(view);
        }

        @Override
        public void onBindViewHolder(TargetViewHolder holder, int position) {
            final TargetConfig t = items.get(position);
            String label = AppRepository.labelOf(MainActivity.this, t.appName);
            if (label == null || label.equals(t.appName)) {
                holder.title.setText(t.appName);
                holder.packageName.setVisibility(View.GONE);
            } else {
                holder.title.setText(label);
                holder.packageName.setText(t.appName);
                holder.packageName.setVisibility(View.VISIBLE);
            }
            android.graphics.drawable.Drawable icon = AppRepository.iconOf(
                    MainActivity.this, t.appName);
            if (icon != null) {
                holder.icon.setImageDrawable(icon);
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            holder.subtitle.setText(t.summary() + (t.scriptId.isEmpty() ? ""
                    : "\n" + new ScriptStore(MainActivity.this).name(t.scriptId)));
            holder.state.setText(t.enabled ? "启用" : "停用");
            holder.state.setBackgroundResource(t.enabled
                    ? R.drawable.bg_state_enabled : R.drawable.bg_state_disabled);
            holder.state.setTextColor(ContextCompat.getColor(MainActivity.this, t.enabled
                    ? R.color.zfm_on_success_container : R.color.zfm_on_surface_variant));
            boolean current = deploymentState == DeploymentState.Status.CURRENT;
            holder.deployState.setText(current ? "已推送" : "待推送");
            holder.deployState.setBackgroundResource(current
                    ? R.drawable.bg_state_enabled : R.drawable.bg_state_disabled);
            holder.deployState.setTextColor(ContextCompat.getColor(MainActivity.this,
                    current ? R.color.zfm_on_success_container : R.color.zfm_warning));
            if (!runtimeStatus.available) {
                holder.runtime.setText("运行状态不可用");
                holder.runtime.setTextColor(ContextCompat.getColor(
                        MainActivity.this, R.color.zfm_on_surface_variant));
            } else {
                int pid = runtimeStatus.pidFor(t.appName);
                holder.runtime.setText(pid > 0 ? "正在运行 · PID " + pid : "当前未运行");
                holder.runtime.setTextColor(ContextCompat.getColor(MainActivity.this,
                        pid > 0 ? R.color.zfm_success : R.color.zfm_on_surface_variant));
            }
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editTarget(t.appName);
                }
            });
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showItemMenu(t);
                    return true;
                }
            });
            holder.more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showItemMenu(t);
                }
            });
        }
    }

    private static final class TargetViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView packageName;
        final TextView subtitle;
        final TextView state;
        final TextView deployState;
        final TextView runtime;
        final View more;

        TargetViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.targetIcon);
            title = view.findViewById(R.id.targetTitle);
            packageName = view.findViewById(R.id.targetPackage);
            subtitle = view.findViewById(R.id.targetSubtitle);
            state = view.findViewById(R.id.targetState);
            deployState = view.findViewById(R.id.targetDeployState);
            runtime = view.findViewById(R.id.targetRuntime);
            more = view.findViewById(R.id.targetMore);
        }
    }
}
