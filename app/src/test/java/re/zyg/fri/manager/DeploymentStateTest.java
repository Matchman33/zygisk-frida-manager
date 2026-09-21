package re.zyg.fri.manager;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.UUID;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DeploymentStateTest {
    @Test public void localChangeBecomesDirtyUntilMarkedApplied() {
        Context context = RuntimeEnvironment.getApplication();
        ConfigStore store = ConfigStore.get(context);
        DeploymentState.markApplied(context);
        assertEquals(DeploymentState.Status.CURRENT, DeploymentState.status(context));

        String name = "com.example." + UUID.randomUUID().toString().replace("-", "");
        TargetConfig target = new TargetConfig(name);
        target.injectedLibraries.add(Prefs.get(context).gadgetPath());
        store.put(target);
        assertEquals(DeploymentState.Status.DIRTY, DeploymentState.status(context));

        store.remove(name);
        assertEquals(DeploymentState.Status.CURRENT, DeploymentState.status(context));
    }
}
