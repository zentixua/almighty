package ua.zentix.almighty.act;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Below owner, a component loses buttons that run or type commands, at any depth (translation arguments too). */
class ChatStripTest {
    @Test
    void runAndSuggestRemovedOpenUrlKept() {
        JsonElement c = JsonParser.parseString("""
                {"text":"[Claim reward]","clickEvent":{"action":"run_command","value":"/op Attacker"},
                 "extra":[{"text":"x","clickEvent":{"action":"suggest_command","value":"/deop Owner"}},
                          {"translate":"chat.type.text","with":[{"text":"y","click_event":{"action":"run_command","command":"/stop"}}]},
                          {"text":"site","clickEvent":{"action":"open_url","value":"https://example.org"}}]}""");
        Chat.strip(c);
        String s = c.toString();
        assertFalse(s.contains("run_command") || s.contains("suggest_command"), s);
        assertTrue(s.contains("open_url"), "harmless clicks stay: " + s);
    }

    /** 26.x: a click that opens a dialog (whose button may run a command) or sends a custom action goes too. */
    @Test
    void dialogAndCustomClicksRemoved() {
        JsonElement c = JsonParser.parseString("""
                [{"text":"OK","click_event":{"action":"show_dialog","dialog":{"type":"minecraft:notice","title":"t",
                   "action":{"label":"OK","action":{"type":"run_command","command":"/gm hi"}}}}},
                 {"text":"y","click_event":{"action":"minecraft:custom","id":"x:y"}},
                 {"text":"z","click_event":{"action":"copy_to_clipboard","value":"z"}}]""");
        Chat.strip(c);
        String s = c.toString();
        assertFalse(s.contains("show_dialog") || s.contains("custom") || s.contains("run_command"), s);
        assertTrue(s.contains("copy_to_clipboard"), s);
    }
}
