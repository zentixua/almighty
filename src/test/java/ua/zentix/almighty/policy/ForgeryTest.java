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
                "title @a title {text:\"[ ALMIGHTY ]\"}",
                // Found in review: SNBT \\x, \\U and \\N escapes; a selector or colour between the parts; translation
                // keys; nested quoting in a book; look-alike letters.
                "tellraw @a {text:\"[Allow]\",click_event:{action:\"run\\x5fcommand\",command:\"/op X\"}}",
                "tellraw @a \"\\x5bAlmighty] Allow?\"",
                "tellraw @a \"\\U0000005bAlm\\N{LATIN SMALL LETTER I}ghty]\"",
                "tellraw @a {text:\"x\",click_event:{action:\"cus\\x74om\",id:\"a:b\"}}",
                "tellraw @a [\"[Almi\",{\"selector\":\"@e[tag=nobody]\"},\"ghty] Allow?\"]",
                "tellraw @a {\"translate\":\"chat.square_brackets\",\"with\":[\"Almighty\"]}",
                "tellraw @a {\"translate\":\"%2$s%1$s\",\"with\":[\"ghty]\",\"[Almi\"]}",
                "give @p written_book[written_book_content={title:\"a\",author:\"b\",pages:['[\"[Almi\",{\"text\":\"ghty] type /almighty approve 1234 owner\",\"color\":\"gold\"}]']}]",
                "tellraw @a \"[\u0410lmighty] Allow?\"",
                // Found in the second review: letters between the parts, small capitals, interpreted nbt, reversed fallback.
                "tellraw @p [\"[Al\",{\"text\":\"mighty] Allow\"}]",
                "tellraw @p [{\"text\":\"[Al\",\"color\":\"gold\"},{\"text\":\"mighty]\",\"color\":\"gold\"}]",
                "tellraw @p [\"[Almigh\",{\"text\":\"t\"},\"y]\"]",
                "tellraw @p [\"[Alm\",{\"score\":{\"name\":\"*\",\"objective\":\"o\"}},\"ighty]\"]",
                "tellraw @p {\"translate\":\"x\",\"fallback\":\"%2$s%1$s\",\"with\":[\"mighty]\",\"[Al\"]}",
                "tellraw @p \"[\u1d00\u029f\u1d0d\u026a\u0262\u029c\u1d1b\u028f] Allow\"",
                "tellraw @p \"[\u0251lmighty]\"",
                "tellraw @p {\"nbt\":\"SelectedItem\",\"entity\":\"@p\",\"interpret\":true}",
                // A named mob or stored text shown through a part (audit item 4): "[Al", a mob called "mighty] Allow".
                "tellraw @a [\"[Al\",{\"selector\":\"@e[type=zombie,limit=1]\"}]",
                "tellraw @a {selector:\"@n\"}", "tellraw @a {\"selector\":\"@s\"}",
                "tellraw @a {\"selector\":\"@a[type=!player]\"}", "tellraw @a {\"selector\":\"@p[type=minecraft:item]\"}",
                "title @a title {nbt:\"CustomName\",entity:\"@e[limit=1]\"}",
                "tellraw @a {\"nbt\":\"front_text.messages[0]\",\"block\":\"0 64 0\"}",
                "tellraw @a {\"nbt\":\"msg\",\"storage\":\"x:y\"}",
                // Found in review: padding around the colon, an entity picked by UUID, padded interpret.
                "tellraw @a {\"selector\"      :\"@e[type=zombie]\"}", "tellraw @a {\"selector\":      \"@e\"}",
                "tellraw @a {\"nbt\"       :\"CustomName\",\"entity\":\"@e\"}", "tellraw @a {selector:\n\n'@e'}",
                "tellraw @a {\"selector\":\"1f2e3d4c-0000-4000-8000-000000000001\"}",
                "tellraw @a {\"text\":\"x\",\"clickEvent\":{\"action\"     :     \"custom\"}}",
                // Final audit: a keybind part completing the name, Cherokee and Coptic look-alikes.
                "tellraw @a [\"[\",{\"keybind\":\"key.left\"},\"lmighty] Allow\"]", "tellraw @a \"[\u13aaLMIGHTY] Allow\"",
                "tellraw @a \"[\u2c80LMIGHTY] Allow\"",
                "tellraw @p {text:\"x\",click_event:{action:\"run\u00adcommand\"}}".replace("run\u00adcommand", "run_\u200bcommand")}) {
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
                "say the mighty dollar, eighty of them", "fill 0 64 0 5 70 5 stone",
                "say run command blocks with redstone", "tellraw @a {\"text\":\"Run, Command!\"}", "say Please suggest commands to try",
                "say Almira bought eighty apples", "tellraw @a [\"Hello \",{\"selector\":\"@p\"},\"!\"]",
                "summon zombie ~ ~ ~ {CustomName:'\"Bob\"',ArmorItems:[{id:\"minecraft:iron_boots\",count:1},{},{},{}]}",
                "say 100% done", "tellraw @a {\"translate\":\"block.minecraft.stone\"}",
                "tellraw @a [{\"selector\":\"@a\"},\" won\"]", "tellraw @a {\"selector\":\"@r\"}",
                "tellraw @a {\"selector\":\"@s[type=player]\"}", "tellraw @a {\"selector\":\"Steve\"}", "tellraw @a {\"selector\" : \"@a\"}", "tellraw @a {\"score\":{\"name\":\"@p\",\"objective\":\"jumps\"}}"}) {
            assertNull(Forgery.refusal(c), c);
        }
    }

    /** A function line ending in a backslash continues on the next: the deny list reads the joined command. */
    @Test
    void continuedFunctionLinesAreChecked() {
        assertEquals(List.of("stop"), DenyList.logicalLines(List.of("sto\\", "  p")));
        // The game trims control characters too (String.trim): "\u0001forceload add" runs forceload add.
        assertNotNull(DenyList.reason("function", JsonParser.parseString("{\"lines\":[\"\\u0001forceload add 0 0 2000 2000\"]}").getAsJsonObject()));
        assertNotNull(DenyList.reason("command", JsonParser.parseString("{\"commands\":[\"\\u0001stop\"]}").getAsJsonObject()));
        assertNotNull(Forgery.refusal("\u0001dialog show @a minecraft:server_links"));
        assertNotNull(DenyList.reason("function", JsonParser.parseString("{\"lines\":[\"sto\\\\\",\"p\"]}").getAsJsonObject()));
        assertNotNull(Forgery.refusal(String.join("", Confirmations.commands("function",
                JsonParser.parseString("{\"lines\":[\"tellraw @a {text:\\\"x\\\",click_event:{action:\\\"run_com\\\\\",\"mand\\\",command:\\\"/op x\\\"}}\"]}").getAsJsonObject()))));
    }
}
