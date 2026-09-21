package re.zyg.fri.manager;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stages every device-side config artifact before replacing any live file. */
final class DeviceConfigTransaction {
    static final class Entry {
        final String staged;
        final String destination;
        final String mode;

        Entry(String staged, String destination, String mode) {
            this.staged = staged;
            this.destination = destination;
            this.mode = mode;
        }
    }

    private final Context context;
    private final String moduleDir;
    private final String root;
    private final List<Entry> entries = new ArrayList<>();

    DeviceConfigTransaction(Context context, String moduleDir) {
        this.context = context.getApplicationContext();
        this.moduleDir = moduleDir;
        root = moduleDir + "/.zfm-transaction-" + UUID.randomUUID().toString();
    }

    Shell.Result begin() {
        return Shell.su(context, "for old in " + Shell.q(moduleDir)
                + "/.zfm-transaction-*; do [ -e \"$old\" ] && rm -rf \"$old\"; done; "
                + "mkdir -p " + Shell.q(root + "/files")
                + " && chmod 0700 " + Shell.q(root));
    }

    Shell.Result stageText(String destination, String content, String mode) {
        String staged = stagedPath();
        Shell.Result result = Shell.writeTextFile(context, staged, content);
        if (!result.ok()) return result;
        result = Shell.su(context, "chmod " + mode + " " + Shell.q(staged)
                + " && chown 0:0 " + Shell.q(staged));
        if (result.ok()) entries.add(new Entry(staged, destination, mode));
        return result;
    }

    Shell.Result stageCopy(String source, String destination, String mode) {
        String staged = stagedPath();
        Shell.Result result = Shell.su(context, "cp " + Shell.q(source) + " " + Shell.q(staged)
                + " && chmod " + mode + " " + Shell.q(staged)
                + " && chown 0:0 " + Shell.q(staged), null, Shell.TIMEOUT_LONG);
        if (result.ok()) entries.add(new Entry(staged, destination, mode));
        return result;
    }

    Shell.Result commit() {
        if (entries.isEmpty()) {
            abort();
            return new Shell.Result(-1, "", "事务中没有待提交文件", "(transaction)", false);
        }
        return Shell.su(context, buildCommitCommand(root, entries), null, Shell.TIMEOUT_LONG);
    }

    void abort() {
        Shell.su(context, "rm -rf " + Shell.q(root));
    }

    private String stagedPath() {
        return root + "/files/" + entries.size();
    }

    static String buildCommitCommand(String root, List<Entry> entries) {
        String backup = root + "/backup";
        StringBuilder rollback = new StringBuilder("rollback() { set +e; ");
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            rollback.append("if [ -e ").append(Shell.q(backup + "/" + i + ".absent"))
                    .append(" ]; then rm -f ").append(Shell.q(entry.destination))
                    .append("; elif [ -e ").append(Shell.q(backup + "/" + i))
                    .append(" ]; then cp -pf ").append(Shell.q(backup + "/" + i))
                    .append(' ').append(Shell.q(entry.destination)).append("; fi; ");
        }
        rollback.append("rm -rf ").append(Shell.q(root)).append("; }");

        StringBuilder command = new StringBuilder("set -e; ")
                .append("mkdir -p ").append(Shell.q(backup)).append("; ")
                .append(rollback).append("; trap 'rollback' 0 1 2 15; ");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int slash = entry.destination.lastIndexOf('/');
            String parent = slash <= 0 ? "/" : entry.destination.substring(0, slash);
            command.append("mkdir -p ").append(Shell.q(parent)).append("; ")
                    .append("if [ -e ").append(Shell.q(entry.destination)).append(" ]; then cp -pf ")
                    .append(Shell.q(entry.destination)).append(' ').append(Shell.q(backup + "/" + i))
                    .append("; else : > ").append(Shell.q(backup + "/" + i + ".absent"))
                    .append("; fi; mv -f ").append(Shell.q(entry.staged)).append(' ')
                    .append(Shell.q(entry.destination)).append("; chmod ").append(entry.mode).append(' ')
                    .append(Shell.q(entry.destination)).append("; chown 0:0 ")
                    .append(Shell.q(entry.destination)).append("; ");
        }
        command.append("trap - 0 1 2 15; rm -rf ").append(Shell.q(root))
                .append("; echo __ZFM_COMMIT_OK__");
        return command.toString();
    }
}
