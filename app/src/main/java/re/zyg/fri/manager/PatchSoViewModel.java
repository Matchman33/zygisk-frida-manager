package re.zyg.fri.manager;

import android.app.Application;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns only cache copies, keeping original document URIs read-only. */
public final class PatchSoViewModel extends AndroidViewModel {
    public static final class State {
        public final boolean busy;
        public final String progress, error, inputName, scriptName, version;
        public final ElfPatcher.Info info;
        public final SoPatchEngine.Result result;
        State(PatchSoViewModel model) {
            busy = model.busy; progress = model.progress; error = model.error;
            inputName = model.inputName; scriptName = model.scriptName; version = model.version;
            info = model.info; result = model.result;
        }
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File directory;
    public final MutableLiveData<State> state = new MutableLiveData<>();
    private volatile boolean cleared;
    private boolean busy;
    private String progress = "", error = "", inputName = "", scriptName = "", version = "";
    private File input, script;
    private ElfPatcher.Info info;
    private SoPatchEngine.Result result;

    public PatchSoViewModel(Application application) {
        super(application);
        directory = new File(application.getCacheDir(), "so-patch-" + UUID.randomUUID());
        if (!directory.mkdirs()) error = "无法创建补丁工作目录";
        try { version = new BundledGadget(application).version(); }
        catch (Exception e) { error = "内置 Gadget 资源不可用"; }
        emit();
    }

    private void emit() { if (!cleared) state.setValue(new State(this)); }
    private void progress(String value) { main.post(() -> { progress = value; emit(); }); }
    private boolean begin(String message) {
        if (busy) return false;
        busy = true; progress = message; error = ""; result = null; emit();
        return true;
    }
    private void finish(Exception failure) {
        main.post(() -> { busy = false; if (failure != null) { error = failure.getMessage(); progress = ""; } emit(); });
        if (cleared) clean(directory);
    }

    public void importSo(Uri uri) {
        if (!begin("正在读取 SO…")) return;
        worker.execute(() -> {
            File candidate = new File(directory, "next-input.so");
            try {
                String name = SoPatchConfig.validateSoName(documentName(uri));
                copyDocument(uri, candidate, ElfPatcher.MAX_SO_BYTES);
                ElfPatcher.Info parsed = ElfPatcher.inspect(candidate);
                File accepted = new File(directory, "input.so");
                if (accepted.exists() && !accepted.delete()) throw new IOException("无法更新本地 SO 副本");
                if (!candidate.renameTo(accepted)) throw new IOException("无法保存本地 SO 副本");
                main.post(() -> { input = accepted; inputName = name; info = parsed; progress = ""; });
                finish(null);
            } catch (Exception failure) { candidate.delete(); finish(failure); }
        });
    }

    public void importScript(Uri uri) {
        if (!begin("正在读取附带脚本…")) return;
        worker.execute(() -> {
            File candidate = new File(directory, "next-script.js");
            try {
                String name = Scripts.displayName(getApplication(), uri);
                if (!name.endsWith(".js") && !name.endsWith(".mjs")) throw new IOException("请选择明文 .js 或 .mjs 脚本");
                copyDocument(uri, candidate, 32L * 1024 * 1024);
                File accepted = new File(directory, "script.js");
                if (accepted.exists() && !accepted.delete()) throw new IOException("无法更新脚本副本");
                if (!candidate.renameTo(accepted)) throw new IOException("无法保存脚本副本");
                main.post(() -> { script = accepted; scriptName = name; progress = ""; });
                finish(null);
            } catch (Exception failure) { candidate.delete(); finish(failure); }
        });
    }

    public void removeScript() {
        if (busy) return;
        if (script != null) script.delete();
        script = null; scriptName = ""; result = null; emit();
    }

    public void build(String config) {
        if (input == null || !begin("正在准备补丁…")) return;
        final File source = input, scriptCopy = script;
        final String name = inputName;
        worker.execute(() -> {
            try {
                SoPatchEngine.Result completed = SoPatchEngine.build(getApplication(), source, name,
                        scriptCopy, config, directory, this::progress);
                main.post(() -> { result = completed; progress = "补丁包已就绪"; });
                finish(null);
            } catch (Exception failure) { finish(failure); }
        });
    }

    public void export(Uri uri) {
        if (busy || result == null) return;
        File archive = result.archive;
        busy = true; error = ""; progress = "正在导出…"; emit();
        worker.execute(() -> {
            try {
                try (InputStream in = new FileInputStream(archive);
                     OutputStream out = getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("无法写入导出位置");
                    SoPatchEngine.copy(in, out);
                }
                main.post(() -> progress = "补丁包已导出");
                finish(null);
            } catch (Exception failure) { finish(failure); }
        });
    }

    private void copyDocument(Uri uri, File file, long limit) throws IOException {
        try (InputStream input = getApplication().getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new IOException("无法读取所选文件");
            byte[] buffer = new byte[65536];
            long total = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > limit) throw new IOException("文件超过大小限制：" + (limit / 1024 / 1024) + " MiB");
                if (cleared) throw new IOException("操作已取消");
                output.write(buffer, 0, n);
            }
            if (total == 0) throw new IOException("所选文件为空");
        }
    }

    private String documentName(Uri uri) throws IOException {
        try (Cursor cursor = getApplication().getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getString(column);
            }
        }
        String last = uri.getLastPathSegment();
        if (last == null) throw new IOException("文件提供器没有返回文件名");
        return new File(last).getName();
    }

    @Override protected void onCleared() {
        cleared = true;
        worker.execute(() -> clean(directory));
        worker.shutdown();
    }

    private static void clean(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
}
