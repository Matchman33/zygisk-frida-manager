package re.zyg.fri.manager;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.lang.ref.WeakReference;

/** Small UI helpers shared by all activities. */
public final class Ui {

    private static WeakReference<Context> appContext = new WeakReference<>(null);

    private Ui() {
    }

    public static void rememberContext(Context ctx) {
        if (ctx != null && appContext.get() == null) {
            appContext = new WeakReference<>(ctx.getApplicationContext());
        }
    }

    public static Context appContext() {
        return appContext.get();
    }

    public static void toast(Context ctx, CharSequence msg) {
        if (ctx == null) {
            return;
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    }

    public static void toastShort(Context ctx, CharSequence msg) {
        if (ctx == null) {
            return;
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    public static Dialog busy(Context ctx, String message) {
        LinearLayout content = new LinearLayout(ctx);
        content.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padding = dp(ctx, 24);
        content.setPadding(padding, padding, padding, padding);
        CircularProgressIndicator progress = new CircularProgressIndicator(ctx);
        progress.setIndeterminate(true);
        progress.setIndicatorSize(dp(ctx, 32));
        content.addView(progress, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));
        android.widget.TextView label = new android.widget.TextView(ctx);
        label.setText(message);
        label.setTextSize(14f);
        label.setPadding(dp(ctx, 16), 0, 0, 0);
        content.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return new MaterialAlertDialogBuilder(ctx).setView(content).setCancelable(false).create();
    }

    /** Shows a message box with a monospaced, scrollable body. */
    public static void report(Activity activity, String title, String body) {
        report(activity, title, body, null);
    }

    /**
     * Same as {@link #report(Activity, String, String)} but runs {@code onDismiss}
     * when the user closes the box.
     *
     * Callers that want to finish the activity after showing a result MUST use
     * this variant: calling {@code finish()} right after {@code report()} tears
     * the dialog down together with the activity, so the user never sees it.
     */
    public static void report(Activity activity, String title, String body,
                              final Runnable onDismiss) {
        if (activity == null || activity.isFinishing()) {
            if (onDismiss != null) {
                onDismiss.run();
            }
            return;
        }
        android.widget.TextView tv = new android.widget.TextView(activity);
        tv.setText(body == null ? "" : body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = dp(activity, 16);
        tv.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView sv = new android.widget.ScrollView(activity);
        sv.addView(tv);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        if (onDismiss != null) {
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    onDismiss.run();
                }
            });
        }
        dialog.show();
    }

    public static void alert(Activity activity, String title, String body) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(body == null ? "" : body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static void help(Activity activity, String title, String body) {
        if (activity.isFinishing()) return;
        android.widget.TextView text = new android.widget.TextView(activity);
        androidx.core.widget.TextViewCompat.setTextAppearance(text, R.style.TextAppearance_Zfm_Body);
        text.setText(body);
        text.setTextSize(13f);
        text.setTextIsSelectable(true);
        text.setLineSpacing(dp(activity, 2), 1f);
        text.setPadding(dp(activity, 24), dp(activity, 4), dp(activity, 24), dp(activity, 24));
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(text);
        new MaterialAlertDialogBuilder(activity).setTitle(title).setView(scroll)
                .setPositiveButton(R.string.action_understood, null).show();
    }

    public static void bindHelp(Activity activity, int viewId, int title, int body) {
        android.view.View view = activity.findViewById(viewId);
        if (view == null) return;
        view.setContentDescription(activity.getString(title) + "，" + activity.getString(R.string.action_help));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(view, activity.getString(title));
        view.setOnClickListener(v -> help(activity, activity.getString(title), activity.getString(body)));
    }

    public interface TextCallback {
        void onText(String value);
    }

    public static void promptText(Activity activity, String title, String initial,
                                  final TextCallback callback) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(initial == null ? "" : initial);
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(activity);
        int pad = dp(activity, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        callback.onText(input.getText().toString().trim());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    public static String shortPath(String path, int max) {
        if (path == null) {
            return "";
        }
        if (path.length() <= max) {
            return path;
        }
        return "…" + path.substring(path.length() - max + 1);
    }
}
