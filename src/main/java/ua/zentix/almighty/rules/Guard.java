package ua.zentix.almighty.rules;

import ua.zentix.almighty.bridge.RpcException;

/**
 * What a declarative rule's creator may do, asked again at every fire (plan 4.12), so a rule stops working when its
 * creator loses the right. Implemented by {@code GmServer} over the gate, zones and claims; the default (no bridge,
 * scripts and rules of the owner) allows everything.
 */
public interface Guard {
    /** Null if the creator still holds {@code node} (rules, engine for script rules, command); else why the rule turns off. */
    String lacks(Declarative.Creator c, ua.zentix.almighty.policy.Node node);

    /** Null if the creator may cancel this event: where they may build, or anywhere for the owner. */
    String cancel(Declarative.Creator c, Object event);

    /** Say as the rule (text already filled in). */
    void say(Declarative.Creator c, String text, String to, String style) throws RpcException;

    /**
     * Run a command as the creator (deny list, zones, the creator's own permission level). {@code rule} names the
     * rule (its name, else {@code #id}): its block writes go to that rule's journal change, so they can be undone.
     */
    void command(Declarative.Creator c, Object event, String command, String rule) throws RpcException;

    /** Whether the creator is the owner (no TTL, no re-checks). */
    boolean owner(Declarative.Creator c);

    Guard OPEN = new Guard() {
        public String lacks(Declarative.Creator c, ua.zentix.almighty.policy.Node node) {
            return null;
        }

        public String cancel(Declarative.Creator c, Object event) {
            return null;
        }

        public void say(Declarative.Creator c, String text, String to, String style) {}

        public void command(Declarative.Creator c, Object event, String command, String rule) {}

        public boolean owner(Declarative.Creator c) {
            return true;
        }
    };
}
