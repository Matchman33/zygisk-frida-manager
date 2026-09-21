package re.zyg.fri.manager;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Root shell plumbing.
 *
 * Works with Magisk (`su -c`), KernelSU (`su -c`) and APatch (`su -c`) through a
 * small candidate list, and never blocks the UI thread.
 *
 * Only API level 1 methods of {@link Process} are used on purpose: minSdk is 23
 * and {@code waitFor(timeout, unit)} / {@code destroyForcibly()} only exist from
 * API 26 on.
 */
public final class Shell {

    public static final long TIMEOUT_SHORT = 20_000L;
    /** generous: the very first su call pops up the root grant dialog */
    public static final long TIMEOUT_FIRST = 90_000L;
    public static final long TIMEOUT_LONG = 180_000L;

    public static final Charset UTF8 = Charset.forName("UTF-8");

    private Shell() {
    }

    // ------------------------------------------------------------------ result

    public static final class Result {
        public final int code;
        public final String stdout;
        public final String stderr;
        public final String command;
        public final boolean timedOut;

        Result(int code, String stdout, String stderr, String command, boolean timedOut) {
            this.code = code;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.command = command == null ? "" : command;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return code == 0 && !timedOut;
        }

        public String out() {
            return stdout.trim();
        }

        public String err() {
            return stderr.trim();
        }

        public String dump() {
            StringBuilder sb = new StringBuilder();
            sb.append("$ ").append(command).append('\n');
            sb.append("exit=").append(code);
            if (timedOut) {
                sb.append(" (超时)");
            }
            sb.append('\n');
            String o = out();
            String e = err();
            if (!o.isEmpty()) {
                sb.append(o).append('\n');
            }
            if (!e.isEmpty()) {
                sb.append("[stderr] ").append(e).append('\n');
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ exec

    private static final class StreamPump extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamPump(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // process died / stream closed: whatever we got is what we report
            }
        }

        String text() {
            return new String(buffer.toByteArray(), UTF8);
        }
    }

    public static Result exec(String[] argv, byte[] stdin, long timeoutMs) {
        return execStream(argv, stdin == null ? null : new ByteArrayInputStream(stdin), timeoutMs);
    }

    /**
     * Same as {@link #exec(String[], byte[], long)} but streams the child's stdin
     * from an arbitrary source, so pushing a 30 MB gadget never needs the whole
     * file in the app's heap.
     */
    public static Result execStream(String[] argv, final InputStream stdinStream, long timeoutMs) {
        StringBuilder cmdline = new StringBuilder();
        for (String a : argv) {
            if (cmdline.length() > 0) {
                cmdline.append(' ');
            }
            cmdline.append(a);
        }

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            proc = pb.start();

            if (stdinStream != null) {
                final Process target = proc;
                Thread feeder = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        OutputStream os = null;
                        try {
                            os = target.getOutputStream();
                            byte[] buf = new byte[64 * 1024];
                            int read;
                            while ((read = stdinStream.read(buf)) != -1) {
                                os.write(buf, 0, read);
                            }
                            os.flush();
                        } catch (IOException ignored) {
                        } finally {
                            if (os != null) {
                                try {
                                    os.close();
                                } catch (IOException ignored) {
                                }
                            }
                            try {
                                stdinStream.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                });
                feeder.setDaemon(true);
                feeder.start();
            } else {
                try {
                    proc.getOutputStream().close();
                } catch (IOException ignored) {
                }
            }

            StreamPump outPump = new StreamPump(proc.getInputStream());
            StreamPump errPump = new StreamPump(proc.getErrorStream());
            outPump.start();
            errPump.start();

            final Process watched = proc;
            final AtomicBoolean finished = new AtomicBoolean(false);
            final AtomicBoolean killed = new AtomicBoolean(false);

            if (timeoutMs > 0) {
                Thread watchdog = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long deadline = System.currentTimeMillis() + timeoutMs;
                        while (System.currentTimeMillis() < deadline) {
                            if (finished.get()) {
                                return;
                            }
                            try {
                                Thread.sleep(120L);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        if (!finished.get()) {
                            killed.set(true);
                            try {
                                watched.destroy();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                watchdog.setDaemon(true);
                watchdog.start();
            }

            int code = proc.waitFor();
            finished.set(true);

            try {
                outPump.join(1500L);
                errPump.join(1500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            return new Result(code, outPump.text(), errPump.text(), cmdline.toString(), killed.get());
        } catch (IOException e) {
            String message = e.toString();
            if (argv.length > 0 && argv[0].endsWith("su")
                    && (message.contains("No such file") || message.contains("error=2"))) {
                message = message + ROOT_HINT;
            }
            return new Result(-1, "", message, cmdline.toString(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            return new Result(-1, "", "interrupted", cmdline.toString(), false);
        }
    }

    // ------------------------------------------------------------------ su

    /** Shell-quotes a single argument for `sh -c`. */
    public static String q(String raw) {
        if (raw == null) {
            return "''";
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    /** Shown when no su could be executed at all - this is by far the most common setup problem. */
    public static final String ROOT_HINT =
            "\n\n找不到可执行的 su。\n"
                    + "KernelSU (Next) 会把 su 从「尚未被授予 root 的应用」的命名空间里隐藏，"
                    + "所以第一次授权必须在 root 管理器里手动完成：\n"
                    + "  · KernelSU Next → 超级用户 / 应用列表 → 允许本应用\n"
                    + "  · Magisk → 超级用户 → 允许本应用\n"
                    + "授权后再回到本页重新检测。\n"
                    + "（对照：授权过的应用能直接看到 /system/bin/su，未授权的会得到 No such file or directory）";

    /** Usual homes of su, tried before falling back to a PATH lookup. */
    private static final String[] SU_ABSOLUTE_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su",
    };

    /**
     * @return the executable names to try, absolute paths first. A path that is
     * not executable for this uid is skipped: on KernelSU the hidden su gives a
     * hard ENOENT, and pre-checking avoids spawning doomed processes.
     */
    private static List<String> suPrograms() {
        List<String> programs = new ArrayList<>();
        for (String path : SU_ABSOLUTE_PATHS) {
            try {
                if (new File(path).canExecute()) {
                    programs.add(path);
                }
            } catch (Throwable ignored) {
            }
        }
        programs.add("su");
        return programs;
    }

    private static List<String[]> candidates(String cmd, String mode) {
        List<String[]> list = new ArrayList<>();
        for (String program : suPrograms()) {
            if (Prefs.SU_C.equals(mode)) {
                list.add(new String[]{program, "-c", cmd});
            } else if (Prefs.SU_0_SH_C.equals(mode)) {
                list.add(new String[]{program, "0", "sh", "-c", cmd});
            } else {
                list.add(new String[]{program, "-c", cmd});
                list.add(new String[]{program, "0", "sh", "-c", cmd});
                list.add(new String[]{program, "root", "-c", cmd});
            }
        }
        return list;
    }

    /**
     * Runs {@code cmd} through {@code sh -c} as root.
     *
     * With {@link Prefs#SU_AUTO} the alternate invocation styles are only tried
     * when the previous one failed in a way that suggests the CLI syntax was
     * wrong (or `su` was not found at all) - a denial is reported as-is.
     */
    public static Result su(Context ctx, String cmd, byte[] stdin, long timeoutMs) {
        String mode = Prefs.get(ctx).suMode();
        List<String[]> list = candidates(cmd, mode);
        Result last = null;
        for (String[] argv : list) {
            Result r = exec(argv, stdin, timeoutMs);
            if (r.ok()) {
                return r;
            }
            last = r;
            if (list.size() == 1) {
                break;
            }
            String all = (r.out() + " " + r.err()).toLowerCase(Locale.ROOT);
            boolean syntaxProblem = r.code == 127 || r.code == 126
                    || all.contains("not found")
                    || all.contains("inaccessible")
                    || all.contains("usage:")
                    || all.contains("invalid option")
                    || all.contains("unknown option");
            if (!syntaxProblem) {
                break;
            }
        }
        return last;
    }

    public static Result su(Context ctx, String cmd) {
        return su(ctx, cmd, null, TIMEOUT_SHORT);
    }

    /**
     * Starts a root command that keeps running, for streaming output (logcat).
     * The caller owns the returned process and must destroy it.
     */
    public static Process startSu(Context ctx, String cmd) throws IOException {
        String[] argv = candidates(cmd, Prefs.get(ctx).suMode()).get(0);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    // ------------------------------------------------------------------ files

    public static boolean exists(Context ctx, String path) {
        Result r = su(ctx, "[ -e " + q(path) + " ] && echo __yes__ || echo __no__");
        return r.out().contains("__yes__");
    }

    public static String readTextFile(Context ctx, String path) {
        Result r = su(ctx, "cat " + q(path));
        if (!r.ok()) {
            return null;
        }
        return r.stdout;
    }

    public static long sizeOf(Context ctx, String path) {
        Result r = su(ctx, "stat -c %s " + q(path));
        if (!r.ok()) {
            return -1L;
        }
        try {
            return Long.parseLong(r.out().trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public static boolean ensureDir(Context ctx, String path) {
        Result r = su(ctx, "mkdir -p " + q(path) + " && chmod 0755 " + q(path) + " && echo __ok__");
        return r.out().contains("__ok__");
    }

    public static boolean chmod(Context ctx, String path, String mode) {
        return su(ctx, "chmod " + mode + " " + q(path)).ok();
    }

    public static boolean chownRoot(Context ctx, String path) {
        su(ctx, "chown 0:0 " + q(path));
        return true;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /**
     * Writes a text file as root and verifies the result by reading it back.
     *
     * Primary path pipes the payload into `cat` (no cross-domain file access
     * involved). If that does not survive (some su implementations fiddle with
     * stdio) it falls back to staging inside the app cache dir and `cp`-ing it,
     * which works because root can read the app's private files.
     */
    public static Result writeTextFile(Context ctx, String path, String content) {
        byte[] data = content.getBytes(UTF8);
        String cmd = "cat > " + q(path);
        Result r = su(ctx, cmd, data, TIMEOUT_SHORT);
        String back = readTextFile(ctx, path);
        if (back != null && normalize(back).equals(normalize(content))) {
            return new Result(0, "written (stdin) " + data.length + " bytes", "", cmd, false);
        }

        String stageError = "";
        try {
            File tmp = new File(ctx.getCacheDir(), "stage-" + Math.abs(path.hashCode()) + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            String cmd2 = "cp " + q(tmp.getAbsolutePath()) + " " + q(path);
            Result r2 = su(ctx, cmd2, null, TIMEOUT_SHORT);
            back = readTextFile(ctx, path);
            if (back != null && normalize(back).equals(normalize(content))) {
                tmp.delete();
                return new Result(0, "written (staged copy) " + data.length + " bytes", "", cmd2, false);
            }
            stageError = "\n-- 回退方案 --\n" + r2.dump();
            tmp.delete();
        } catch (IOException e) {
            stageError = "\n-- 回退方案失败 --\n" + e;
        }

        String detail = "\n-- 直接写入 --\n" + r.dump() + stageError;
        if (back != null) {
            detail += "\n-- 读回校验 --\n期望 " + data.length + " 字节, 实际读到 "
                    + back.length() + " 字符";
        } else {
            detail += "\n-- 读回校验 --\n无法读取 " + path;
        }
        return new Result(-1, "", detail, cmd, false);
    }

    /** Pushes a local file (any size) to a device path as root. */
    public static Result pushFile(Context ctx, File local, String devicePath, String mode) {
        if (!local.isFile()) {
            return new Result(-1, "", "本地文件不存在: " + local, "push", false);
        }
        long localSize = local.length();

        String cpCommand = "cp " + q(local.getAbsolutePath()) + " " + q(devicePath)
                + " && chmod " + mode + " " + q(devicePath) + " && echo __ok__";
        Result cpResult = su(ctx, cpCommand, null, TIMEOUT_LONG);
        if (cpResult.out().contains("__ok__") && sizeOf(ctx, devicePath) == localSize) {
            return new Result(0, "已推送 " + localSize + " 字节 → " + devicePath, "", cpCommand, false);
        }

        // Fallback: stream the file into `cat` on stdin. Slower, but it does not
        // need the root shell to be able to read the app's private files - some
        // SELinux policies deny that, which breaks `cp` only.
        String catCommand = "cat > " + q(devicePath);
        Result last = cpResult;
        for (String[] argv : candidates(catCommand, Prefs.get(ctx).suMode())) {
            FileInputStream fis;
            try {
                fis = new FileInputStream(local);
            } catch (IOException e) {
                return new Result(-1, "", e.toString(), catCommand, false);
            }
            last = execStream(argv, fis, TIMEOUT_LONG);
            if (sizeOf(ctx, devicePath) == localSize) {
                su(ctx, "chmod " + mode + " " + q(devicePath));
                return new Result(0, "已推送(流式) " + localSize + " 字节 → " + devicePath, "",
                        catCommand, false);
            }
        }

        return new Result(-1, "",
                "cp 与流式写入都失败。\n-- cp --\n" + cpResult.dump()
                        + "-- 流式 --\n" + last.dump()
                        + "设备上大小 " + sizeOf(ctx, devicePath) + " / 本地 " + localSize,
                cpCommand, false);
    }

    /** Cheap sanity test that root works at all. */
    public static Result rootCheck(Context ctx, boolean firstCall) {
        return su(ctx, "id", null, firstCall ? TIMEOUT_FIRST : TIMEOUT_SHORT);
    }

    public static boolean hasRoot(Context ctx) {
        Result r = rootCheck(ctx, false);
        return r.ok() && r.out().contains("uid=0");
    }
}
