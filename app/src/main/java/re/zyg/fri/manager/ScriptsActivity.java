package re.zyg.fri.manager;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.TooltipCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;

public class ScriptsActivity extends BaseActivity {
    private ScriptStore store;
    private RecyclerView list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new ScriptStore(this);
        setContentView(R.layout.activity_scripts);
        setTitle(R.string.nav_script);
        list = findViewById(R.id.scriptList);
        list.setLayoutManager(new LinearLayoutManager(this));
        View add = findViewById(R.id.addScript);
        TooltipCompat.setTooltipText(add, getString(R.string.add_script));
        add.setOnClickListener(v -> editName(null));
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<ScriptStore.Entry> entries = store.entries();
        ((TextView) findViewById(R.id.scriptCount)).setText(getString(R.string.script_count, entries.size()));
        list.setAdapter(new RecyclerView.Adapter<Row>() {
            @Override public Row onCreateViewHolder(ViewGroup parent, int type) {
                return new Row(getLayoutInflater().inflate(R.layout.item_script, parent, false));
            }
            @Override public void onBindViewHolder(Row row, int position) {
                ScriptStore.Entry entry = entries.get(position);
                Prefs prefs = Prefs.forScript(ScriptsActivity.this, entry.id);
                int count = 0;
                for (TargetConfig target : ConfigStore.get(ScriptsActivity.this).targets()) {
                    if (entry.id.equals(target.scriptId)) count++;
                }
                row.title.setText(entry.name);
                String file = prefs.scriptName().isEmpty() ? "尚未导入文件" : prefs.scriptName();
                row.subtitle.setText(file + " · " + count + " 个应用"
                        + (prefs.scriptLockedBySubConfig() ? " · 已锁定" : ""));
                row.itemView.setOnClickListener(v -> open(entry.id));
                row.more.setVisibility(entry.id.isEmpty() ? View.GONE : View.VISIBLE);
                TooltipCompat.setTooltipText(row.more, getString(R.string.action_more));
                row.more.setOnClickListener(v -> {
                    PopupMenu menu = new PopupMenu(ScriptsActivity.this, v);
                    menu.getMenu().add(0, 1, 0, "重命名");
                    menu.getMenu().add(0, 2, 1, "删除");
                    menu.setOnMenuItemClickListener(item -> {
                        if (item.getItemId() == 1) editName(entry);
                        else new MaterialAlertDialogBuilder(ScriptsActivity.this)
                                .setTitle("删除脚本配置")
                                .setMessage(entry.name + "\n\n引用此脚本的应用将切换为默认配置，推送并应用后生效。")
                                .setNegativeButton(R.string.action_cancel, null)
                                .setPositiveButton("删除", (dialog, which) -> {
                                    store.remove(entry.id);
                                    refresh();
                                }).show();
                        return true;
                    });
                    menu.show();
                });
            }
            @Override public int getItemCount() { return entries.size(); }
        });
    }

    private void editName(ScriptStore.Entry entry) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.script_name);
        if (entry != null) input.setText(entry.name);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(entry == null ? "添加脚本" : "重命名")
                .setView(input).setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(-1).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("请输入脚本名称"); return; }
            if (entry == null) open(store.create(name).id);
            else store.rename(entry.id, name);
            dialog.dismiss();
            refresh();
        }));
        dialog.show();
    }

    private void open(String id) {
        startActivity(new Intent(this, GadgetConfigActivity.class)
                .putExtra(EXTRA_DETAIL, true).putExtra(GadgetConfigActivity.EXTRA_SCRIPT_ID, id));
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final View more;
        Row(View view) {
            super(view);
            title = view.findViewById(R.id.scriptTitle);
            subtitle = view.findViewById(R.id.scriptSubtitle);
            more = view.findViewById(R.id.scriptMore);
        }
    }
}
