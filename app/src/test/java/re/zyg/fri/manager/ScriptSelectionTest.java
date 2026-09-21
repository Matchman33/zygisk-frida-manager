package re.zyg.fri.manager;

import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ScriptSelectionTest {
    @Test public void packageIsDisplayOnlyAndPickerChangesTheSavedTarget() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(context, TargetEditActivity.class)
                .putExtra(TargetEditActivity.EXTRA_APP_NAME, "com.example.original");
        try (ActivityController<TargetEditActivity> controller = Robolectric
                .buildActivity(TargetEditActivity.class, intent).setup()) {
            TargetEditActivity activity = controller.get();
            android.widget.TextView packageView = activity.findViewById(R.id.appNameInput);
            assertFalse(packageView instanceof android.widget.EditText);
            assertNull(packageView.getKeyListener());
            assertTrue(packageView.isTextSelectable());
            activity.findViewById(R.id.pickAppButton).performClick();
            org.robolectric.shadows.ShadowActivity.IntentForResult picker =
                    org.robolectric.Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(AppPickerActivity.class.getName(), picker.intent.getComponent().getClassName());
            activity.onActivityResult(picker.requestCode, android.app.Activity.RESULT_OK,
                    new Intent().putExtra(AppPickerActivity.EXTRA_PACKAGE, "com.example.selected"));
            assertEquals("com.example.selected", packageView.getText().toString());
            controller.recreate();
            activity = controller.get();
            assertEquals("com.example.selected", ((android.widget.TextView)
                    activity.findViewById(R.id.appNameInput)).getText().toString());
            activity.findViewById(R.id.saveButton).performClick();
            assertNotNull(ConfigStore.get(context).find("com.example.selected"));
            assertNull(ConfigStore.get(context).find("com.example.original"));
        }
    }

    @Test public void editorFallsBackWhenItsScriptWasDeletedOnAnotherPage() {
        Context context = RuntimeEnvironment.getApplication();
        ScriptStore scripts = new ScriptStore(context);
        ScriptStore.Entry entry = scripts.create("Temporary");
        TargetConfig target = new TargetConfig("com.example.deleted");
        target.scriptId = entry.id;
        target.injectedLibraries.add(Prefs.get(context).gadgetPath());
        ConfigStore config = ConfigStore.get(context);
        config.put(target);
        Intent intent = new Intent(context, TargetEditActivity.class)
                .putExtra(TargetEditActivity.EXTRA_APP_NAME, target.appName);
        try (ActivityController<TargetEditActivity> controller = Robolectric
                .buildActivity(TargetEditActivity.class, intent).setup()) {
            controller.pause().stop();
            scripts.remove(entry.id);
            controller.start().resume();
            controller.get().findViewById(R.id.saveButton).performClick();
            config.reload();
            assertEquals("", config.find(target.appName).scriptId);
        }
    }

    @Test public void targetSelectsLibraryEntryAndPersistsIt() {
        Context context = RuntimeEnvironment.getApplication();
        ScriptStore.Entry entry = new ScriptStore(context).create("Example script");
        Intent intent = new Intent(context, TargetEditActivity.class)
                .putExtra(TargetEditActivity.EXTRA_APP_NAME, "com.example.ui");
        try (ActivityController<TargetEditActivity> controller = Robolectric
                .buildActivity(TargetEditActivity.class, intent).setup()) {
            TargetEditActivity activity = controller.get();
            activity.findViewById(R.id.pushScriptButton).performClick();
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("Example script", dialog.getListView().getAdapter().getItem(1));
            dialog.getListView().performItemClick(null, 1, 1);
            activity.findViewById(R.id.saveButton).performClick();
            ConfigStore config = ConfigStore.get(context);
            config.reload();
            assertEquals(entry.id, config.find("com.example.ui").scriptId);
        }
    }

    @Test public void importedProfileHidesKeyAndLocksBothEditors() {
        Context context = RuntimeEnvironment.getApplication();
        ScriptStore.Entry entry = new ScriptStore(context).create("Imported");
        Prefs prefs = Prefs.forScript(context, entry.id);
        prefs.setScriptKey("must-not-display");
        prefs.setScriptKeyHidden(true);
        prefs.setScriptLockedBySubConfig(true);
        Intent intent = new Intent(context, GadgetConfigActivity.class)
                .putExtra(GadgetConfigActivity.EXTRA_SCRIPT_ID, entry.id);
        try (ActivityController<GadgetConfigActivity> controller = Robolectric
                .buildActivity(GadgetConfigActivity.class, intent).setup()) {
            GadgetConfigActivity activity = controller.get();
            assertEquals("", ((android.widget.EditText) activity.findViewById(R.id.encKeyInput)).getText().toString());
            assertFalse(activity.findViewById(R.id.rawJsonInput).isEnabled());
            assertFalse(activity.findViewById(R.id.modeSpinner).isEnabled());
            assertFalse(activity.findViewById(R.id.pickScriptButton).isEnabled());
            assertEquals("must-not-display", prefs.scriptKey());
        }
    }

    @Test public void cryptoControlsRemainDraftUntilSave() {
        Context context = RuntimeEnvironment.getApplication();
        ScriptStore.Entry entry = new ScriptStore(context).create("Draft");
        Prefs prefs = Prefs.forScript(context, entry.id);
        prefs.setScriptMode(Prefs.MODE_PLAIN);
        prefs.setScriptKey("old-key");
        Intent intent = new Intent(context, GadgetConfigActivity.class)
                .putExtra(GadgetConfigActivity.EXTRA_SCRIPT_ID, entry.id);
        try (ActivityController<GadgetConfigActivity> controller = Robolectric
                .buildActivity(GadgetConfigActivity.class, intent).setup()) {
            GadgetConfigActivity activity = controller.get();
            ((android.widget.Spinner) activity.findViewById(R.id.scriptModeSpinner)).setSelection(3);
            ((android.widget.EditText) activity.findViewById(R.id.encKeyInput)).setText("new-key");
            assertEquals(Prefs.MODE_PLAIN, prefs.scriptMode());
            assertEquals("old-key", prefs.scriptKey());
        }
    }

    @Test public void changedCryptoForExistingScriptRequiresReimport() {
        Context context = RuntimeEnvironment.getApplication();
        ScriptStore.Entry entry = new ScriptStore(context).create("Existing");
        Prefs prefs = Prefs.forScript(context, entry.id);
        prefs.setScriptMode(Prefs.MODE_PLAIN);
        prefs.setScriptKey("old-key");
        prefs.setScriptName("hook.js");
        prefs.setScriptPath(prefs.scriptsDir() + "/hook.js");
        prefs.setGadgetConfigJson(GadgetHelper.buildScriptConfig(prefs.scriptPath(), false, true));
        Intent intent = new Intent(context, GadgetConfigActivity.class)
                .putExtra(GadgetConfigActivity.EXTRA_SCRIPT_ID, entry.id);
        try (ActivityController<GadgetConfigActivity> controller = Robolectric
                .buildActivity(GadgetConfigActivity.class, intent).setup()) {
            GadgetConfigActivity activity = controller.get();
            ((android.widget.Spinner) activity.findViewById(R.id.scriptModeSpinner)).setSelection(3);
            ((android.widget.EditText) activity.findViewById(R.id.encKeyInput)).setText("new-key");
            activity.findViewById(R.id.saveGadgetButton).performClick();
            assertEquals(Prefs.MODE_PLAIN, prefs.scriptMode());
            assertEquals("old-key", prefs.scriptKey());
            assertTrue(ShadowDialog.getLatestDialog().isShowing());
        }
    }
}
