package re.zyg.fri.manager;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks a target application.
 *
 * Two ways in: an installed app from the list, or a standalone APK file from
 * disk (which can also be installed onto the device through root, so
 * "select APK -> inject" is a single flow).
 */
public class AppPickerActivity extends BaseActivity {

    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_LABEL = "label";

    private static final int REQ_OPEN_APK = 2001;

    private EditText searchInput;
    private CheckBox showSystem;
    private TextView countText;
    private TextView emptyText;
    private AppAdapter adapter;

    private final List<AppEntry> shown = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        searchInput = findViewById(R.id.searchInput);
        showSystem = findViewById(R.id.showSystem);
        countText = findViewById(R.id.countText);
        emptyText = findViewById(R.id.emptyText);
        RecyclerView appList = findViewById(R.id.appList);

        adapter = new AppAdapter();
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });

        showSystem.setChecked(Prefs.get(this).showSystemApps());
        showSystem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get(AppPickerActivity.this).setShowSystemApps(isChecked);
                applyFilter();
            }
        });

        loadApps();
    }

    private void loadApps() {
        final Dialog busy = Ui.busy(this, "正在读取应用列表…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                AppRepository.invalidate();
                AppRepository.all(AppPickerActivity.this);
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        applyFilter();
                    }
                });
            }
        });
    }

    private void applyFilter() {
        shown.clear();
        shown.addAll(AppRepository.matching(this, searchInput.getText().toString(),
                showSystem.isChecked()));
        adapter.notifyDataSetChanged();
        countText.setText(shown.size() + " 个");
        emptyText.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void returnResult(AppEntry entry) {
        Intent data = new Intent();
        data.putExtra(EXTRA_PACKAGE, entry.packageName);
        data.putExtra(EXTRA_LABEL, entry.label);
        setResult(RESULT_OK, data);
        finish();
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.app_picker, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_pick_apk) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "*/*",
            });
            try {
                startActivityForResult(intent, REQ_OPEN_APK);
            } catch (Throwable t) {
                Ui.toast(this, "没有可用的文件选择器: " + t);
            }
            return true;
        }
        if (id == R.id.menu_refresh) {
            loadApps();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN_APK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        handlePickedApk(uri);
    }

    private void handlePickedApk(final Uri uri) {
        final Dialog busy = Ui.busy(this, "正在解析 APK…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                String name = Scripts.displayName(AppPickerActivity.this, uri);
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    name = name + ".apk";
                }
                File local = null;
                String error = null;
                try {
                    local = Scripts.copyToCache(AppPickerActivity.this, uri, name);
                } catch (IOException e) {
                    error = e.toString();
                }
                final File file = local;
                final String failure = error;
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        if (failure != null) {
                            Ui.alert(AppPickerActivity.this, "读取 APK 失败", failure);
                            return;
                        }
                        AppEntry entry = AppRepository.fromApkFile(AppPickerActivity.this,
                                file.getAbsolutePath());
                        if (entry == null) {
                            Ui.alert(AppPickerActivity.this, "解析失败",
                                    "无法从该文件读取包信息，可能不是有效的 APK。");
                            return;
                        }
                        showApkDialog(entry, file);
                    }
                });
            }
        });
    }

    private void showApkDialog(final AppEntry entry, final File file) {
        String message = "应用名: " + entry.label
                + "\n包名: " + entry.packageName
                + "\n文件: " + file.getName()
                + "\n大小: " + (file.length() / 1024 / 1024) + " MB"
                + "\n\n" + (AppRepository.find(this, entry.packageName) != null
                ? "该包已在设备上安装，可直接选择。"
                : "该包当前未安装（或版本不同）。");

        new MaterialAlertDialogBuilder(this)
                .setTitle(entry.label)
                .setMessage(message)
                .setPositiveButton("选择此包名", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        returnResult(entry);
                    }
                })
                .setNeutralButton("安装到设备并选择", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        installApk(entry, file);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void installApk(final AppEntry entry, final File file) {
        final Dialog busy = Ui.busy(this, "正在通过 root 安装 " + entry.packageName + "…");
        busy.show();
        Bg.run(new Runnable() {
            @Override
            public void run() {
                // The package installer runs as system, so the file has to live
                // somewhere world readable - exactly what `adb install` does.
                final String remote = "/data/local/tmp/zfm-install.apk";
                Shell.Result push = Shell.pushFile(AppPickerActivity.this, file, remote, "0644");
                if (!push.ok()) {
                    final String dump = push.dump();
                    Bg.ui(new Runnable() {
                        @Override
                        public void run() {
                            busy.dismiss();
                            Ui.report(AppPickerActivity.this, "推送 APK 失败", dump);
                        }
                    });
                    return;
                }
                final Shell.Result install = Shell.su(AppPickerActivity.this,
                        "pm install -r -d " + Shell.q(remote), null, Shell.TIMEOUT_LONG);
                Bg.ui(new Runnable() {
                    @Override
                    public void run() {
                        busy.dismiss();
                        String text = install.dump();
                        if (install.out().contains("Success") || install.out().contains("success")) {
                            Ui.toast(AppPickerActivity.this, "安装成功: " + entry.packageName);
                            AppRepository.invalidate();
                            returnResult(entry);
                        } else {
                            Ui.report(AppPickerActivity.this, "安装未成功", text);
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ adapter

    private final class AppAdapter extends RecyclerView.Adapter<AppViewHolder> {

        @Override
        public int getItemCount() {
            return shown.size();
        }

        public AppEntry getItem(int position) {
            return shown.get(position);
        }

        @Override
        public AppViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new AppViewHolder(view);
        }

        @Override
        public void onBindViewHolder(AppViewHolder holder, int position) {
            final AppEntry entry = shown.get(position);
            if (entry.icon != null) {
                holder.icon.setImageDrawable(entry.icon);
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            holder.label.setText(entry.label);
            holder.packageName.setText(entry.packageName);
            holder.system.setVisibility(entry.system ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    returnResult(entry);
                }
            });
        }
    }

    private static final class AppViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final TextView system;

        AppViewHolder(View view) {
            super(view);
            icon = view.findViewById(R.id.appIcon);
            label = view.findViewById(R.id.appLabel);
            packageName = view.findViewById(R.id.appPackage);
            system = view.findViewById(R.id.appSystem);
        }
    }
}
