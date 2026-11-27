package ua.zentix.almighty.rules;

import net.neoforged.bus.api.Event;
import ua.zentix.almighty.bridge.RpcException;

/**
 * What a declarative rule's creator may do, asked again at every fire (plan 4.12), so a rule stops working when its
 * creator loses the right. Implemented by {@code GmServer} over the gate, zones and claims; the default (no bridge,
 * scripts and rules of the owner) allows everything.
 */
public interface Guard {
    /** Null if the creator may still have rules at all; else why the rule turns off. */
    String rules(Declarative.Creator c);

    /** Null if the creator may cancel this event: where they may build, or anywhere for the owner. */
    String cancel(Declarative.Creator c, Event event);

    /** Say as the rule (text already filled in). */
    void say(Declarative.Creator c, String text, String to, String style) throws RpcException;

    /** Run a command as the creator (deny list, zones, the creator's own permission level). */
    void command(Declarative.Creator c, Event event, String command) throws RpcException;

    /** Whether the creator is the owner (no TTL, no re-checks). */
    boolean owner(Declarative.Creator c);

    Guard OPEN = new Guard() {
        public String rules(Declarative.Creator c) {
            return null;
        }

        public String cancel(Declarative.Creator c, Event event) {
            return null;
        }

        public void say(Declarative.Creator c, String text, String to, String style) {}

        public void command(Declarative.Creator c, Event event, String command) {}

        public boolean owner(Declarative.Creator c) {
            return true;
        }
    };
}
