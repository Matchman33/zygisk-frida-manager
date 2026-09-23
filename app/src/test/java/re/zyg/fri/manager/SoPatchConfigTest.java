package re.zyg.fri.manager;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoPatchConfigTest {
    @Test public void defaultListenDoesNotBlockHost() throws Exception {
        JSONObject interaction = new JSONObject(SoPatchConfig.validate(SoPatchConfig.preset(0, "127.0.0.1", 27042), false)).getJSONObject("interaction");
        assertEquals("resume", interaction.getString("on_load"));
        assertEquals("pick-next", interaction.getString("on_port_conflict"));
    }
    @Test public void waitingPresetIsExplicit() throws Exception {
        JSONObject interaction = new JSONObject(SoPatchConfig.preset(1, "127.0.0.1", 27042)).getJSONObject("interaction");
        assertEquals("wait", interaction.getString("on_load"));
    }
    @Test public void scriptPresetRequiresPayloadAndUsesExtractableName() throws Exception {
        String config = SoPatchConfig.preset(2, "", 0);
        assertThrows(JSONException.class, () -> SoPatchConfig.validate(config, false));
        assertEquals("libgadget.script.so", new JSONObject(SoPatchConfig.validate(config, true))
                .getJSONObject("interaction").getString("path"));
    }
    @Test public void customConfigPreservesAdvancedKeys() throws Exception {
        String config = "{\"runtime\":\"v8\",\"interaction\":{\"type\":\"listen\",\"port\":27042,\"token\":\"token-test\"}}";
        JSONObject root = new JSONObject(SoPatchConfig.validate(config, false));
        assertEquals("v8", root.getString("runtime"));
        assertEquals("token-test", root.getJSONObject("interaction").getString("token"));
    }
    @Test public void invalidPortAndScriptPlacementAreRejected() {
        assertThrows(JSONException.class, () -> SoPatchConfig.validate(SoPatchConfig.preset(0, "127.0.0.1", 65536), false));
        assertThrows(JSONException.class, () -> SoPatchConfig.validate("{\"interaction\":{\"type\":\"listen\",\"port\":1.5}}", false));
        assertThrows(JSONException.class, () -> SoPatchConfig.validate(SoPatchConfig.preset(0, "127.0.0.1", 27042), true));
        assertThrows(JSONException.class, () -> SoPatchConfig.validate("{\"interaction\":{\"type\":\"script\",\"path\":\"/sdcard/test.js\"}}", true));
        assertThrows(JSONException.class, () -> SoPatchConfig.validate("{}", false));
    }
    @Test public void namesCannotEscapeArchiveOrOverwriteGadget() {
        assertEquals("libtarget.so", SoPatchConfig.validateSoName("libtarget.so"));
        assertEquals("libc++_shared.so", SoPatchConfig.validateSoName("libc++_shared.so"));
        assertThrows(IllegalArgumentException.class, () -> SoPatchConfig.validateSoName("lib/../../evil.so"));
        assertThrows(IllegalArgumentException.class, () -> SoPatchConfig.validateSoName("libgadget.so"));
        assertThrows(IllegalArgumentException.class, () -> SoPatchConfig.validateSoName("libgadget.config.so"));
        assertThrows(IllegalArgumentException.class, () -> SoPatchConfig.validateSoName("libgadget.script.so"));
    }
}
