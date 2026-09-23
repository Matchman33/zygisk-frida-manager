package re.zyg.fri.manager;

import android.widget.EditText;
import android.widget.TextView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PatchSoActivityTest {
    @Test public void editedPortSurvivesActivityRecreation() {
        try (ActivityController<PatchSoActivity> controller = Robolectric.buildActivity(PatchSoActivity.class).setup()) {
            ((EditText) controller.get().findViewById(R.id.patchPort)).setText("28000");
            controller.recreate();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("28000", ((EditText) controller.get().findViewById(R.id.patchPort)).getText().toString());
        }
    }

    @Test public void initialEditorShowsBundledVersionAndRequiresInput() {
        try (ActivityController<PatchSoActivity> controller = Robolectric.buildActivity(PatchSoActivity.class).setup()) {
            PatchSoActivity activity = controller.get();
            assertTrue(((TextView) activity.findViewById(R.id.patchGadgetVersion)).getText().toString().contains("17.18.0"));
            assertFalse(activity.findViewById(R.id.patchBuild).isEnabled());
            assertFalse(activity.findViewById(R.id.patchExport).isEnabled());
        }
    }

    @Test public void customEditorReceivesEditedPresetWithoutChangingSavedSettings() throws Exception {
        try (ActivityController<PatchSoActivity> controller = Robolectric.buildActivity(PatchSoActivity.class).setup()) {
            PatchSoActivity activity = controller.get();
            String previous = Prefs.get(activity).gadgetConfigJson();
            ((EditText) activity.findViewById(R.id.patchPort)).setText("28000");
            ((MaterialButtonToggleGroup) activity.findViewById(R.id.patchConfigMode)).check(R.id.patchCustomMode);
            JSONObject config = new JSONObject(((EditText) activity.findViewById(R.id.patchJson)).getText().toString());
            assertEquals(28000, config.getJSONObject("interaction").getInt("port"));
            assertEquals("resume", config.getJSONObject("interaction").getString("on_load"));
            assertEquals(previous, Prefs.get(activity).gadgetConfigJson());
        }
    }
}
