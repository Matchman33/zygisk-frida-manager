package re.zyg.fri.manager;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class InjectionPlanTest {
    private static final String DIR = "/data/local/tmp/re.zyg.fri";
    private static final List<String> GADGETS = Arrays.asList(DIR + "/libgadget.so",
            DIR + "/libgadget32.so", DIR + "/libgadget-child.so");

    private TargetConfig target(String name) {
        TargetConfig t = new TargetConfig(name);
        t.scriptId = "script-one";
        t.injectedLibraries.add(GADGETS.get(0));
        return t;
    }

    @Test public void twoApplicationsGetDifferentGadgetsAndConfigs() {
        InjectionPlan a = new InjectionPlan(target("com.example.a"), DIR, GADGETS);
        InjectionPlan b = new InjectionPlan(target("com.example.b"), DIR, GADGETS);
        assertNotEquals(a.copies.get(0).destination, b.copies.get(0).destination);
        assertNotEquals(GadgetHelper.configPathFor(a.copies.get(0).destination),
                GadgetHelper.configPathFor(b.copies.get(0).destination));
    }

    @Test public void preservesLocalPathsAndUnrelatedLibrariesAndMetadata() throws Exception {
        TargetConfig original = target("com.example.a");
        original.injectedLibraries.add("/custom/libfeature.so");
        original.lock(TargetConfig.LOCK_SCRIPT);
        InjectionPlan plan = new InjectionPlan(original, DIR, GADGETS);
        assertEquals(GADGETS.get(0), original.injectedLibraries.get(0));
        assertEquals("/custom/libfeature.so", plan.target.injectedLibraries.get(1));
        assertEquals("script-one", plan.target.scriptId);
        assertTrue(plan.target.isLocked(TargetConfig.LOCK_SCRIPT));
        assertFalse(plan.target.toJson().has("script_id"));
        assertFalse(plan.target.toJson().has("locked"));
    }

    @Test public void preserves32BitArchitectureAndIsolatesChildren() {
        TargetConfig original = target("com.example.a:remote");
        original.injectedLibraries.set(0, GADGETS.get(1));
        original.childGatingEnabled = true;
        original.childGatingMode = TargetConfig.MODE_INJECT;
        original.childGatingLibraries.add(GADGETS.get(2));
        InjectionPlan plan = new InjectionPlan(original, DIR, GADGETS);
        assertEquals(2, plan.copies.size());
        assertEquals(GADGETS.get(1), plan.copies.get(0).source);
        assertTrue(plan.copies.get(0).destination.endsWith("/libgadget32.so"));
        assertTrue(plan.copies.get(1).destination.contains("/child/"));
        assertEquals(plan.copies.get(0).destination,
                new InjectionPlan(original, DIR, GADGETS).copies.get(0).destination);
    }

    @Test(expected = IllegalArgumentException.class) public void refusesSilentlyUnusedScript() {
        new InjectionPlan(new TargetConfig("com.example.a"), DIR, GADGETS);
    }

    @Test public void deviceImportRestoresEditableSourcePaths() throws Exception {
        TargetConfig original = target("com.example.a");
        InjectionPlan plan = new InjectionPlan(original, DIR, GADGETS);
        TargetConfig imported = TargetConfig.fromJson(plan.target.toJson());
        plan.restoreSources(imported);
        assertEquals(original.injectedLibraries, imported.injectedLibraries);
    }
}
