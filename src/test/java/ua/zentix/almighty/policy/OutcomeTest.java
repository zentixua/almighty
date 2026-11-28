package ua.zentix.almighty.policy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lead's F1: a confirmed call that ran but failed is told as failed, for commands and functions alike. */
class OutcomeTest {
    @Test
    void failedCommandsAndFunctions() {
        assertTrue(Decisions.failed(JsonParser.parseString("[{\"ok\":true},{\"ok\":false,\"needs_level\":3}]")));
        assertFalse(Decisions.failed(JsonParser.parseString("[{\"ok\":true}]")));
        assertTrue(Decisions.failed(JsonParser.parseString("{\"ok\":false,\"error\":\"line 2\"}")), "a function's result");
        assertFalse(Decisions.failed(JsonParser.parseString("{\"ok\":true}")));
        assertFalse(Decisions.failed(JsonParser.parseString("{\"kind\":\"build\",\"id\":3}")), "other results");
    }
}
