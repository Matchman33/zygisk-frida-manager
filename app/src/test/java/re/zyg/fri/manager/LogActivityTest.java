package re.zyg.fri.manager;

import android.os.Bundle;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LogActivityTest {
    @Test public void selectingAllWhilePausedDoesNotStartCapture() {
        Bundle state = new Bundle();
        state.putBoolean("capture", false);
        try (ActivityController<LogActivity> controller = Robolectric.buildActivity(LogActivity.class)
                .create(state).start().resume().visible()) {
            LogActivity activity = controller.get();
            activity.findViewById(R.id.tagButton).performClick();
            android.app.Dialog shown = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog) shown;
            dialog.getListView().performItemClick(null, 4, 4);
            assertEquals("全部", ((Button) activity.findViewById(R.id.tagButton)).getText().toString());
            assertEquals(activity.getString(R.string.action_start),
                    ((Button) activity.findViewById(R.id.startStopButton)).getText().toString());
        }
    }
}
