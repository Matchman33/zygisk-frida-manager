package re.zyg.fri.manager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Resolves isolated gadget copies without modifying the locally edited library paths. */
final class InjectionPlan {
    static final class Copy {
        final String source;
        final String destination;
        Copy(String source, String destination) {
            this.source = source;
            this.destination = destination;
        }
    }

    final TargetConfig target;
    final List<Copy> copies = new ArrayList<>();

    InjectionPlan(TargetConfig original, String moduleDir, List<String> gadgets) {
        target = original.copy();
        String dir = moduleDir + "/targets/" + identity(original.appName);
        resolve(target.injectedLibraries, dir + "/main", gadgets);
        if (target.childGatingEnabled && TargetConfig.MODE_INJECT.equals(target.childGatingMode)) {
            resolve(target.childGatingLibraries, dir + "/child", gadgets);
        }
        if (copies.isEmpty()) throw new IllegalArgumentException(
                original.appName + ": 所选脚本需要一个 Gadget 注入库");
    }

    private void resolve(List<String> libs, String dir, List<String> gadgets) {
        for (int i = 0; i < libs.size(); i++) {
            String source = libs.get(i);
            if (!gadgets.contains(source)) continue;
            String destination = dir + "/" + i + "/" + source.substring(source.lastIndexOf('/') + 1);
            copies.add(new Copy(source, destination));
            libs.set(i, destination);
        }
    }

    void restoreSources(TargetConfig imported) {
        for (Copy copy : copies) {
            java.util.Collections.replaceAll(imported.injectedLibraries, copy.destination, copy.source);
            java.util.Collections.replaceAll(imported.childGatingLibraries, copy.destination, copy.source);
        }
    }

    static String identity(String name) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
