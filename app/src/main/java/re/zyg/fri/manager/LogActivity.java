package re.zyg.fri.manager;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.TooltipCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Live logcat with a bounded buffer and one owned process per capture. */
public class LogActivity extends BaseActivity {
    private static final String[] FILTERS = {
            "ZygiskFrida", "ZFM", "Frida", "ZygiskFrida ZFM Frida", ""};
    private static final String[] FILTER_LABELS = {
            "ZygiskFrida", "ZFM (loader)", "Frida", "全部关键标签", "全部"};

    private static final int MAX_CHARS = 64_000;
    private static final int TRIM_TO = 48_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (session != null) flush(session);
            handler.postDelayed(this, 250);
        }
    };
    private TextView logText;
    private TextView logStatus;
    private ScrollView logScroll;
    private Button startStopButton;
    private Button tagButton;
    private StreamSession session;
    private boolean captureRequested = true;
    private boolean foreground;
    private int filterIndex;

    private static final class StreamSession {
        volatile Process process;
        volatile boolean stopped;
        final LogBuffer buffer = new LogBuffer();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        logText = findViewById(R.id.logText);
        logStatus = findViewById(R.id.logStatus);
        logScroll = findViewById(R.id.logScroll);
        startStopButton = findViewById(R.id.startStopButton);
        tagButton = findViewById(R.id.tagButton);
        if (savedInstanceState != null) {
            filterIndex = Math.max(0, Math.min(FILTERS.length - 1, savedInstanceState.getInt("filter", 0)));
            captureRequested = savedInstanceState.getBoolean("capture", true);
            logText.setText(savedInstanceState.getString("log", ""));
        }
        updateTagButton();
        startStopButton.setOnClickListener(v -> {
            captureRequested = session == null;
            if (captureRequested) startStream();
            else stopStream();
        });
        tagButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("日志标签")
                .setSingleChoiceItems(FILTER_LABELS, filterIndex, (dialog, which) -> {
                    if (which != filterIndex) {
                        filterIndex = which;
                        updateTagButton();
                        stopStream();
                        if (captureRequested && foreground) startStream();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null).show());
        View clear = findViewById(R.id.clearButton);
        View copy = findViewById(R.id.copyButton);
        TooltipCompat.setTooltipText(clear, getString(R.string.action_clear));
        TooltipCompat.setTooltipText(copy, getString(R.string.action_copy));
        clear.setOnClickListener(v -> {
            if (session != null) session.buffer.clear();
            logText.setText("");
        });
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("logcat", logText.getText()));
                Ui.toastShort(this, "已复制");
            }
        });
    }

    private void updateTagButton() {
        tagButton.setText(FILTER_LABELS[filterIndex]);
    }

    private void updateCaptureState() {
        boolean running = session != null;
        startStopButton.setText(running ? R.string.action_stop : R.string.action_start);
        logStatus.setText(running ? "正在采集" : "已暂停");
    }

    private void startStream() {
        if (session != null || !foreground) return;
        final StreamSession current = new StreamSession();
        session = current;
        updateCaptureState();
        String filter = FILTERS[filterIndex];
        // -T counts all buffered lines before tag filtering, hiding sparse injection logs.
        final String command = filter.isEmpty() ? "logcat -v time -T 300" : "logcat -v time -s " + filter;
        append("--- " + FILTER_LABELS[filterIndex] + " ---");
        Bg.run(() -> {
            try {
                // Closing stdin ends this capture's child, without killing other logcat clients.
                current.process = Shell.startSu(this, command
                        + " & log_pid=$!; read -r stop; kill \"$log_pid\" 2>/dev/null; wait \"$log_pid\"");
                if (current.stopped) signalStop(current);
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(current.process.getInputStream(), Shell.UTF8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (current.stopped) break;
                        current.buffer.append(line);
                    }
                }
            } catch (Exception error) {
                Bg.ui(() -> {
                    if (session == current && !current.stopped) append("[!] " + error.getMessage());
                });
            } finally {
                signalStop(current);
                if (current.process != null) current.process.destroy();
                Bg.ui(() -> {
                    if (session == current) {
                        flush(current);
                        session = null;
                        captureRequested = false;
                        updateCaptureState();
                    }
                });
            }
        });
    }

    private static void signalStop(StreamSession current) {
        Process process = current.process;
        if (process == null) return;
        try {
            process.getOutputStream().write('\n');
            process.getOutputStream().close();
        } catch (Exception ignored) {
            process.destroy();
        }
    }

    private void stopStream() {
        StreamSession current = session;
        session = null;
        if (current != null) {
            current.stopped = true;
            flush(current);
            // A blocked reader must not occupy every shared worker during rapid switches.
            new Thread(() -> signalStop(current), "logcat-stop").start();
        }
        updateCaptureState();
    }

    private void append(String line) {
        appendBatch(line + "\n");
    }

    private void flush(StreamSession current) {
        String batch = current.buffer.drain();
        if (!batch.isEmpty()) appendBatch(batch);
    }

    private void appendBatch(String batch) {
        boolean follow = !logScroll.canScrollVertically(1);
        StringBuilder text = new StringBuilder(logText.getText()).append(batch);
        if (text.length() > MAX_CHARS) text.delete(0, text.length() - TRIM_TO);
        logText.setText(text);
        if (follow) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        handler.post(refresh);
        if (captureRequested) startStream();
        else updateCaptureState();
    }

    @Override
    protected void onStop() {
        foreground = false;
        handler.removeCallbacks(refresh);
        stopStream();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("filter", filterIndex);
        state.putBoolean("capture", captureRequested);
        String text = logText.getText().toString();
        state.putString("log", text.substring(Math.max(0, text.length() - 20_000)));
        super.onSaveInstanceState(state);
    }
}
