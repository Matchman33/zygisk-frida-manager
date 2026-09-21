package re.zyg.fri.manager;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The local list of injection targets.
 *
 * It is kept in {@code filesDir/targets.json} using exactly the same schema as
 * the module expects on the device, so "push" is a plain copy of
 * {@link #toConfigJson()} to /data/local/tmp/re.zyg.fri/config.json.
 */
public final class ConfigStore {

    private static final String FILE_NAME = "targets.json";
    private static final Comparator<TargetConfig> BY_NAME = new Comparator<TargetConfig>() {
        @Override
        public int compare(TargetConfig a, TargetConfig b) {
            return a.appName.compareToIgnoreCase(b.appName);
        }
    };

    private static ConfigStore instance;

    private final Context ctx;
    private final File file;
    private final List<TargetConfig> targets = new ArrayList<>();
    private boolean loaded;

    private ConfigStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.file = new File(this.ctx.getFilesDir(), FILE_NAME);
    }

    public static synchronized ConfigStore get(Context ctx) {
        if (instance == null) {
            instance = new ConfigStore(ctx);
        }
        return instance;
    }

    // ------------------------------------------------------------------ access

    public List<TargetConfig> targets() {
        ensureLoaded();
        Collections.sort(targets, BY_NAME);
        return targets;
    }

    public TargetConfig find(String appName) {
        ensureLoaded();
        if (appName == null) {
            return null;
        }
        for (TargetConfig t : targets) {
            if (appName.equals(t.appName)) {
                return t;
            }
        }
        return null;
    }

    public void put(TargetConfig target) {
        ensureLoaded();
        TargetConfig existing = find(target.appName);
        if (existing == null) {
            targets.add(target);
        } else {
            targets.remove(existing);
            targets.add(target);
        }
        save();
    }

    public void remove(String appName) {
        ensureLoaded();
        TargetConfig existing = find(appName);
        if (existing != null) {
            targets.remove(existing);
            save();
        }
    }

    public boolean isEmpty() {
        return targets().isEmpty();
    }

    // ------------------------------------------------------------------ disk

    public void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        recoverAtomic(file);
        if (!file.isFile()) {
            return;
        }
        String json = readFile(file);
        if (json != null) {
            try {
                JSONArray array = new JSONObject(json).getJSONArray("targets");
                for (int i = 0; i < array.length(); i++) {
                    TargetConfig target = TargetConfig.fromJson(array.getJSONObject(i));
                    if (!target.appName.isEmpty()) targets.add(target);
                }
            } catch (Exception e) {
                Ui.toast(ctx, "读取本机配置失败: " + e.getMessage());
            }
        }
        loadLocks();
    }

    public void reload() {
        loaded = false;
        targets.clear();
        ensureLoaded();
    }

    public boolean save() {
        boolean configSaved = false;
        try {
            writeAtomic(file, toConfigJson());
            configSaved = true;
        } catch (Exception e) {
            Ui.toast(ctx, "保存本机配置失败: " + e);
        }
        return configSaved && saveLocks();
    }

    // ------------------------------------------------------------------ locks

    /**
     * Per target lock flags live in a sidecar file: the device config.json has a
     * fixed schema (the module parses it strictly) and must not learn about our
     * local-only bookkeeping.
     */
    private File metaFile() {
        return new File(ctx.getFilesDir(), "targets.meta.json");
    }

    public void setLocks(String appName, java.util.Collection<String> locked) {
        ensureLoaded();
        TargetConfig t = find(appName);
        if (t == null) {
            return;
        }
        t.locked.clear();
        if (locked != null) {
            t.locked.addAll(locked);
        }
        saveLocks();
    }

    public void clearLocks(String appName) {
        setLocks(appName, null);
    }

    private boolean saveLocks() {
        JSONObject root = new JSONObject();
        try {
            for (TargetConfig t : targets) {
                if (t.locked.isEmpty() && t.scriptId.isEmpty()) {
                    continue;
                }
                JSONObject entry = new JSONObject();
                entry.put("locked", new JSONArray(new ArrayList<>(t.locked)));
                entry.put("script_id", t.scriptId);
                root.put(t.appName, entry);
            }
            writeAtomic(metaFile(), root.toString(2));
            return true;
        } catch (Exception error) {
            Ui.toast(ctx, "保存本机配置元数据失败: " + error.getMessage());
            return false;
        }
    }

    private void loadLocks() {
        File meta = metaFile();
        recoverAtomic(meta);
        if (!meta.isFile()) {
            return;
        }
        String json = readFile(meta);
        if (json == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject(json);
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String app = keys.next();
                TargetConfig t = find(app);
                if (t == null) {
                    continue;
                }
                JSONObject entry = root.optJSONObject(app);
                if (entry != null) t.scriptId = entry.optString("script_id", "");
                JSONArray arr = entry == null ? null : entry.optJSONArray("locked");
                if (arr == null) {
                    continue;
                }
                t.locked.clear();
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i, "");
                    if (!v.isEmpty()) {
                        t.locked.add(v);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ json

    public String toConfigJson() {
        ensureLoaded();
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            List<TargetConfig> sorted = new ArrayList<>(targets);
            Collections.sort(sorted, BY_NAME);
            for (TargetConfig t : sorted) {
                array.put(t.toJson());
            }
            root.put("targets", array);
            return GadgetHelper.prettify(root) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化本机目标配置", e);
        }
    }

    /**
     * Parses a config.json payload.
     *
     * @param replace when true the local list is replaced, otherwise entries are
     *                merged (existing app_name wins is false: device wins)
     * @return null on success, otherwise a human readable error
     */
    public String mergeFromJson(String json, boolean replace) {
        ensureLoaded();
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return "不是合法的 JSON: " + e.getMessage();
        }
        JSONArray array = root.optJSONArray("targets");
        if (array == null) {
            return "缺少 targets 数组";
        }
        List<TargetConfig> parsed = new ArrayList<>();
        int added = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            try {
                TargetConfig t = TargetConfig.fromJson(o);
                if (t.appName.isEmpty()) {
                    continue;
                }
                parsed.add(t);
                added++;
            } catch (Exception e) {
                return "第 " + i + " 项解析失败: " + e.getMessage();
            }
        }

        java.util.Map<String, TargetConfig> previous = new java.util.HashMap<>();
        for (TargetConfig target : targets) previous.put(target.appName, target);
        List<TargetConfig> next = replace ? new ArrayList<>() : new ArrayList<>(targets);
        for (TargetConfig t : parsed) {
            try {
                TargetConfig existing = previous.get(t.appName);
                if (existing != null) {
                    t.scriptId = existing.scriptId;
                    t.locked.addAll(existing.locked);
                    if (!existing.scriptId.isEmpty()) {
                        Prefs prefs = Prefs.get(ctx);
                        try {
                            InjectionPlan plan = new InjectionPlan(existing, prefs.moduleDir(),
                                    java.util.Arrays.asList(prefs.gadgetPath(), prefs.gadget32Path(), prefs.childGadgetPath()));
                            plan.restoreSources(t);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    next.remove(existing);
                }
                next.add(t);
            } catch (Exception e) {
                return t.appName + " 合并失败: " + e.getMessage();
            }
        }
        List<TargetConfig> old = new ArrayList<>(targets);
        targets.clear();
        targets.addAll(next);
        if (!save()) {
            targets.clear();
            targets.addAll(old);
            return "本机配置写入失败，已保留原配置";
        }
        Ui.toastShort(ctx, "已导入 " + added + " 个目标");
        return null;
    }

    private static void writeAtomic(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temporary);
        try {
            out.write(text.getBytes(Shell.UTF8));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (temporary.renameTo(target)) return;

        File backup = new File(parent, target.getName() + ".bak");
        if (backup.exists() && !backup.delete()) {
            temporary.delete();
            throw new IOException("无法清理旧备份 " + backup);
        }
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            temporary.delete();
            throw new IOException("无法备份 " + target);
        }
        if (!temporary.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            temporary.delete();
            throw new IOException("无法替换 " + target);
        }
        if (backup.exists()) backup.delete();
    }

    private static void recoverAtomic(File target) {
        File parent = target.getParentFile();
        if (parent == null) return;
        File backup = new File(parent, target.getName() + ".bak");
        File temporary = new File(parent, target.getName() + ".tmp");
        if (!target.exists() && backup.exists()) backup.renameTo(target);
        if (!target.exists() && temporary.exists()) temporary.renameTo(target);
        if (target.exists() && backup.exists()) backup.delete();
        if (target.exists() && temporary.exists()) temporary.delete();
    }

    private static String readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return new String(bos.toByteArray(), Shell.UTF8);
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
