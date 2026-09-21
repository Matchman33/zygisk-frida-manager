package re.zyg.fri.manager;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable script identities; settings are stored in a separate Prefs scope. */
public final class ScriptStore {
    public static final class Entry {
        public final String id;
        public final String name;

        Entry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Context context;
    private final SharedPreferences index;

    public ScriptStore(Context context) {
        this.context = context.getApplicationContext();
        index = this.context.getSharedPreferences("script_library", Context.MODE_PRIVATE);
    }

    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        result.add(new Entry("", "默认配置"));
        for (Map.Entry<String, ?> item : index.getAll().entrySet()) {
            if (item.getValue() instanceof String) {
                result.add(new Entry(item.getKey(), (String) item.getValue()));
            }
        }
        java.util.Collections.sort(result.subList(1, result.size()),
                (a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    public boolean contains(String id) {
        return id.isEmpty() || index.contains(id);
    }

    public String name(String id) {
        return id.isEmpty() ? "默认配置" : index.getString(id, "脚本已删除");
    }

    public Entry create(String name) {
        String id = UUID.randomUUID().toString();
        index.edit().putString(id, name).apply();
        Prefs prefs = Prefs.forScript(context, id);
        prefs.setGadgetConfigEnabled(true);
        prefs.setGadgetConfigJson(GadgetHelper.buildScriptConfig(prefs.scriptPath(), false, true));
        return new Entry(id, name);
    }

    public void rename(String id, String name) {
        if (!id.isEmpty() && contains(id)) index.edit().putString(id, name).apply();
    }

    public void remove(String id) {
        if (id.isEmpty()) throw new IllegalArgumentException("Cannot remove default settings");
        ConfigStore config = ConfigStore.get(context);
        boolean changed = false;
        for (TargetConfig target : config.targets()) {
            if (id.equals(target.scriptId)) {
                target.scriptId = "";
                changed = true;
            }
        }
        if (changed) config.save();
        index.edit().remove(id).apply();
        context.getSharedPreferences("script_" + id, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
