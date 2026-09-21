package re.zyg.fri.manager;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny threading helper. Every root/shell operation must run off the UI thread.
 */
public final class Bg {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bg() {
    }

    public static void run(Runnable r) {
        POOL.execute(r);
    }

    public static void ui(Runnable r) {
        MAIN.post(r);
    }

    public static void ui(Runnable r, long delayMs) {
        MAIN.postDelayed(r, delayMs);
    }

    /** Runs {@code work} on a worker thread and {@code done} back on the UI thread. */
    public static void run(final Runnable work, final Runnable done) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    work.run();
                } catch (Throwable t) {
                    final Throwable err = t;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(Ui.appContext(), "后台任务异常: " + err);
                        }
                    });
                } finally {
                    if (done != null) {
                        MAIN.post(done);
                    }
                }
            }
        });
    }
}
