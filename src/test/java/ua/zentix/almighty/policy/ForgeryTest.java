package ua.zentix.almighty.policy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plan 4.8: below owner no command plants a click that acts as whoever clicks it, or passes for the server's own
 * prompt, however it is spelled (the ways the independent audit found past the plain text search).
 */
class ForgeryTest {
    @Test
    void forgedClicksAndPrefixesAreRefused() {
        for (String c : new String[] {
                "tellraw @a {\"text\":\"[Allow]\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op x\"}}",
                // 26.x SNBT with escapes; a sign; nested escapes.
                "tellraw @a {text:\"x\",click_event:{action:\"run_com\\u006dand\",command:\"/op x\"}}",
                "setblock 0 64 0 oak_sign{front_text:{messages:['{\"text\":\"a\",\"clickEvent\":{\"action\":\"run\\\\u005fcommand\",\"value\":\"/op x\"}}','\"\"','\"\"','\"\"']}}",
                // Dialogs (26.x): a click opening an inline dialog whose button runs a command, a custom action, the dialog command.
                "tellraw @a {text:\"OK\",click_event:{action:\"show_dialog\",dialog:{type:\"notice\",title:\"t\",action:{label:\"OK\",action:{type:\"run_command\",command:\"/gm hi\"}}}}}",
                "tellraw @a {text:\"OK\",click_event:{action:\"custom\",id:\"x:y\"}}",
                "dialog show @a {type:\"notice\",title:\"Almighty\"}",
                "execute as @a run dialog show @s minecraft:server_links",
                // The prefix, whole, split across parts, spaced, in other case.
                "tellraw @a \"[Almighty] Allow?\"",
                "tellraw @a [\"[Almi\",\"ghty] Allow?\"]",
                "tellraw @a [{\"text\":\"[Almi\",\"color\":\"gold\"},{\"text\":\"ghty]\"}]",
                "title @a title {text:\"[ ALMIGHTY ]\"}"}) {
            assertNotNull(Forgery.refusal(c), c);
        }
    }

    @Test
    void ordinaryCommandsPass() {
        for (String c : new String[] {
                "tellraw @a {\"text\":\"Welcome\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"https://example.org\"}}",
                "tellraw @a {text:\"copy\",click_event:{action:\"copy_to_clipboard\",value:\"x\"}}",
                "scoreboard objectives add jumps minecraft.custom:minecraft.jump",
                "give @p diamond_sword[custom_name='\"Excalibur\"']",
                "say the almighty dollar", "fill 0 64 0 5 70 5 stone"}) {
            assertNull(Forgery.refusal(c), c);
        }
    }

    /** A function line ending in a backslash continues on the next: the deny list reads the joined command. */
    @Test
    void continuedFunctionLinesAreChecked() {
        assertEquals(List.of("stop"), DenyList.logicalLines(List.of("sto\\", "  p")));
        assertNotNull(DenyList.reason("function", JsonParser.parseString("{\"lines\":[\"sto\\\\\",\"p\"]}").getAsJsonObject()));
        assertNotNull(Forgery.refusal(String.join("", Confirmations.commands("function",
                JsonParser.parseString("{\"lines\":[\"tellraw @a {text:\\\"x\\\",click_event:{action:\\\"run_com\\\\\",\"mand\\\",command:\\\"/op x\\\"}}\"]}").getAsJsonObject()))));
    }
}
