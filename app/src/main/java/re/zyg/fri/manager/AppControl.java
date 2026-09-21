package re.zyg.fri.manager;

/** Package lifecycle operations, with process verification before reporting success. */
final class AppControl {
    interface Commands {
        Shell.Result run(String command);
    }

    private AppControl() { }

    static String packageName(String processName) {
        int colon = processName.indexOf(':');
        return colon < 0 ? processName : processName.substring(0, colon);
    }

    static Shell.Result stop(String processName, int userId, Commands commands) {
        String name = packageName(processName);
        if (!name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || userId < 0) {
            return new Shell.Result(-1, "", "无法识别应用包名: " + name, "", false);
        }
        String command = "am force-stop --user " + userId + " " + Shell.q(name);
        Shell.Result stopped = commands.run(command);
        if (!stopped.ok()) return stopped;
        for (int attempt = 0; attempt < 6; attempt++) {
            Shell.Result processes = commands.run("ps -A -o UID,NAME");
            if (!processes.ok()) return processes;
            if (!isRunning(processes.stdout, name, userId)) return stopped;
            try {
                Thread.sleep(100);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new Shell.Result(-1, "", "停止检查已中断", command, false);
            }
        }
        return new Shell.Result(-1, "", "停止后仍检测到应用进程: " + name, command, false);
    }

    private static boolean isRunning(String output, String name, int userId) {
        for (String line : output.split("\n")) {
            String[] columns = line.trim().split("\\s+", 2);
            if (columns.length != 2) continue;
            try {
                int uid = Integer.parseInt(columns[0]);
                if (uid / 100000 == userId
                        && (columns[1].equals(name) || columns[1].startsWith(name + ":"))) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // ps starts with a UID/NAME header.
            }
        }
        return false;
    }
}
