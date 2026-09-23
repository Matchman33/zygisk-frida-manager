package re.zyg.fri.manager;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Live logcat with a bounded buffer and one owned process per capture. */
public class LogActivity extends BaseActivity {
    private static final String[] FILTERS = {
            "ZygiskFrida", "ZFM", "Frida", "ZygiskFrida ZFM Frida ZFM-Script", "", "ZFM-Script"};
    private static final String[] FILTER_LABELS = {
            "ZygiskFrida", "ZFM (loader)", "Frida", "全部关键标签", "全部", "脚本 console"};

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
    private int filterIndex = 3;
    private SharedPreferences logPrefs;
    private long clearedThrough;
    private LogCursor cursor;
    private final SimpleDateFormat displayTime = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private static final class StreamSession {
        volatile Process process;
        volatile boolean stopped;
        final LogBuffer buffer = new LogBuffer();
        final LogCursor.Reader reader;
        StreamSession(LogCursor.Reader reader) { this.reader = reader; }
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
        logPrefs = getSharedPreferences("log_view", MODE_PRIVATE);
        clearedThrough = logPrefs.getLong("cleared_through_us", 0L);
        cursor = new LogCursor(clearedThrough);
        filterIndex = Math.max(0, Math.min(FILTERS.length - 1, logPrefs.getInt("filter", 3)));
        if (savedInstanceState != null) {
            filterIndex = Math.max(0, Math.min(FILTERS.length - 1, savedInstanceState.getInt("filter", filterIndex)));
            captureRequested = savedInstanceState.getBoolean("capture", true);
            if (savedInstanceState.getLong("clear", clearedThrough) == clearedThrough) {
                logText.setText(savedInstanceState.getString("log", ""));
                cursor.restore(savedInstanceState.getLong("cursor"), savedInstanceState.getStringArrayList("boundary"));
            }
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
                        stopStream();
                        filterIndex = which;
                        logPrefs.edit().putInt("filter", which).apply();
                        cursor = new LogCursor(clearedThrough);
                        logText.setText("");
                        updateTagButton();
                        if (captureRequested && foreground) startStream();
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null).show());
        View clear = findViewById(R.id.clearButton);
        View copy = findViewById(R.id.copyButton);
        TooltipCompat.setTooltipText(clear, getString(R.string.action_clear));
        TooltipCompat.setTooltipText(copy, getString(R.string.action_copy));
        clear.setOnClickListener(v -> clearLogs());
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

    private void clearLogs() {
        stopStream();
        clearedThrough = System.currentTimeMillis() * 1000L;
        logPrefs.edit().putLong("cleared_through_us", clearedThrough).apply();
        cursor = new LogCursor(clearedThrough);
        logText.setText("");
        if (captureRequested && foreground) startStream();
        Ui.toastShort(this, "已清空，从现在开始显示新日志");
    }

    private void updateCaptureState() {
        boolean running = session != null;
        startStopButton.setText(running ? R.string.action_stop : R.string.action_start);
        logStatus.setText(running ? "正在采集" : "已暂停");
    }

    private void startStream() {
        if (session != null || !foreground) return;
        final StreamSession current = new StreamSession(cursor.reader());
        session = current;
        updateCaptureState();
        String filter = FILTERS[filterIndex];
        // Timestamp-based -T preserves sparse logs and supports a persistent clear boundary.
        final String command = "logcat -b main -b system -b crash -v threadtime -v epoch -v usec"
                + (cursor.latest() > 0 ? " -T " + Shell.q(cursor.since()) : filter.isEmpty() ? " -T 300" : "")
                + (filter.isEmpty() ? "" : " -s " + filter);
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
        if (batch.isEmpty()) return;
        StringBuilder accepted = new StringBuilder();
        for (String line : batch.split("\n")) {
            long timestamp = LogCursor.timestamp(line);
            if (timestamp >= 0) {
                if (current.reader.accept(line, timestamp)) {
                    String record = line.trim();
                    accepted.append(displayTime.format(new Date(timestamp / 1000)))
                            .append(record.substring(record.indexOf(' '))).append('\n');
                }
            } else if (!line.startsWith("---------")) {
                accepted.append(line).append('\n');
            }
        }
        if (accepted.length() > 0) appendBatch(accepted.toString());
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
        state.putLong("clear", clearedThrough);
        state.putLong("cursor", cursor.latest());
        state.putStringArrayList("boundary", new ArrayList<>(cursor.boundaryLines()));
        String text = logText.getText().toString();
        state.putString("log", text.substring(Math.max(0, text.length() - 20_000)));
        super.onSaveInstanceState(state);
    }
}
