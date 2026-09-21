package re.zyg.fri.manager;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ScriptStoreTest {
    @Test public void profilesDoNotOverwriteEachOtherOrLegacySettings() {
        Context ctx = RuntimeEnvironment.getApplication();
        Prefs legacy = Prefs.get(ctx);
        legacy.setScriptKey("legacy-key");
        ScriptStore store = new ScriptStore(ctx);
        ScriptStore.Entry one = store.create("First");
        ScriptStore.Entry two = store.create("Second");
        Prefs first = Prefs.forScript(ctx, one.id);
        Prefs second = Prefs.forScript(ctx, two.id);
        first.setScriptKey("first-key");
        second.setScriptKey("second-key");
        first.setScriptMode(Prefs.MODE_ENCRYPT_DEVICE);
        second.setScriptMode(Prefs.MODE_PLAIN);
        assertEquals("first-key", Prefs.forScript(ctx, one.id).scriptKey());
        assertEquals("second-key", Prefs.forScript(ctx, two.id).scriptKey());
        assertEquals("legacy-key", legacy.scriptKey());
        assertNotEquals(first.scriptsDir(), second.scriptsDir());
        assertEquals(legacy.moduleDir(), first.moduleDir());
    }

    @Test public void bindingAndLocksSurviveReloadAndDeviceReplacement() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        ConfigStore config = ConfigStore.get(ctx);
        ScriptStore store = new ScriptStore(ctx);
        ScriptStore.Entry entry = store.create("Bound");
        TargetConfig target = new TargetConfig("com.example.bound");
        target.scriptId = entry.id;
        target.lock(TargetConfig.LOCK_SCRIPT);
        target.injectedLibraries.add(Prefs.get(ctx).gadgetPath());
        config.put(target);
        config.reload();
        assertEquals(entry.id, config.find(target.appName).scriptId);
        assertTrue(config.find(target.appName).isLocked(TargetConfig.LOCK_SCRIPT));
        String deviceJson = config.toConfigJson();
        assertFalse(deviceJson.contains("script_id"));
        assertNull(config.mergeFromJson(deviceJson, true));
        config.reload();
        assertEquals(entry.id, config.find(target.appName).scriptId);
        assertTrue(config.find(target.appName).isLocked(TargetConfig.LOCK_SCRIPT));
        store.remove(entry.id);
        assertFalse(store.contains(entry.id));
        config.reload();
        assertEquals("", config.find(target.appName).scriptId);
        assertTrue(config.find(target.appName).isLocked(TargetConfig.LOCK_SCRIPT));
        assertEquals(target.injectedLibraries, config.find(target.appName).injectedLibraries);
    }

    @Test public void deletingScriptResetsEveryReferenceButPreservesOtherBindings() {
        Context ctx = RuntimeEnvironment.getApplication();
        ConfigStore config = ConfigStore.get(ctx);
        ScriptStore scripts = new ScriptStore(ctx);
        ScriptStore.Entry removed = scripts.create("Removed");
        ScriptStore.Entry kept = scripts.create("Kept");
        for (int i = 0; i < 3; i++) {
            TargetConfig target = new TargetConfig("com.example.app" + i);
            target.scriptId = i == 2 ? kept.id : removed.id;
            target.enabled = i != 1;
            config.put(target);
        }
        scripts.remove(removed.id);
        config.reload();
        assertEquals("", config.find("com.example.app0").scriptId);
        assertEquals("", config.find("com.example.app1").scriptId);
        assertFalse(config.find("com.example.app1").enabled);
        assertEquals(kept.id, config.find("com.example.app2").scriptId);
        assertTrue(scripts.contains(kept.id));
    }
}
