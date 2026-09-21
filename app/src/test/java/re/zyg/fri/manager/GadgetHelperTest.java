package re.zyg.fri.manager;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GadgetHelperTest {
    @Test
    public void formEditPreservesAdvancedSettings() throws Exception {
        String original = "{\"runtime\":\"v8\",\"interaction\":{\"type\":\"listen\","
                + "\"token\":\"private-token\",\"port\":27042,\"on_load\":\"wait\"}}";
        JSONObject result = new JSONObject(GadgetHelper.mergeForm(original,
                GadgetHelper.buildListenConfig("127.0.0.2", 27050, false, "fail")));
        assertEquals("v8", result.getString("runtime"));
        JSONObject interaction = result.getJSONObject("interaction");
        assertEquals("private-token", interaction.getString("token"));
        assertEquals("127.0.0.2", interaction.getString("address"));
        assertEquals(27050, interaction.getInt("port"));
        assertEquals("resume", interaction.getString("on_load"));
    }

    @Test
    public void changingToScriptRemovesNetworkSettingsAndWait() throws Exception {
        String original = "{\"runtime\":\"qjs\",\"interaction\":{\"type\":\"listen\","
                + "\"port\":27042,\"token\":\"private-token\",\"on_load\":\"wait\"}}";
        JSONObject result = new JSONObject(GadgetHelper.mergeForm(original,
                GadgetHelper.buildScriptConfig("/data/local/tmp/test.js", false, true)));
        JSONObject interaction = result.getJSONObject("interaction");
        assertEquals("qjs", result.getString("runtime"));
        assertEquals("script", interaction.getString("type"));
        assertEquals("/data/local/tmp/test.js", interaction.getString("path"));
        assertEquals("reload", interaction.getString("on_change"));
        assertFalse(interaction.has("port"));
        assertFalse(interaction.has("token"));
        assertFalse(interaction.has("on_load"));
    }

    @Test
    public void disablingReloadRemovesItButKeepsScriptParameters() throws Exception {
        String original = "{\"interaction\":{\"type\":\"script\",\"path\":\"/old.js\","
                + "\"on_change\":\"reload\",\"parameters\":{\"level\":2}}}";
        JSONObject result = new JSONObject(GadgetHelper.mergeForm(original,
                GadgetHelper.buildScriptConfig("/new.js", false, false))).getJSONObject("interaction");
        assertEquals("/new.js", result.getString("path"));
        assertEquals(2, result.getJSONObject("parameters").getInt("level"));
        assertFalse(result.has("on_change"));
    }

    @Test
    public void invalidBaseCanBeReplacedByExplicitFormGeneration() throws Exception {
        String generated = GadgetHelper.buildListenConfig("", 0, true, "pick-next");
        JSONObject result = new JSONObject(GadgetHelper.mergeForm("invalid", generated));
        assertEquals(27042, result.getJSONObject("interaction").getInt("port"));
    }
}
