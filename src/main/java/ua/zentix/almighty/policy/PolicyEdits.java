package ua.zentix.almighty.policy;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Comparing two policies (plan 4.10): whether a new policy is only more restrictive than the old one (then the agent
 * may apply it on its own initiative), and which changes are host-level (only the console may make them).
 */
public final class PolicyEdits {
    private PolicyEdits() {}

    private static int openness(Policy.Preset p) {
        return switch (p) {
            case PUBLIC -> 0;
            case FRIENDS -> 1;
            case PRIVATE -> 2;
        };
    }

    private static int openness(Identity.Mode m) {
        return switch (m) {
            case MOJANG -> 0;
            case AUTO, PROXY -> 1;
            case OFFLINE -> 2;
        };
    }

    /**
     * What the new policy grants that the old did not (or a change of owners); null if nothing: a change that only
     * restricts. Openness is what the agent may do unasked: public (guest) < friends (member) < private.
     */
    public static String grants(Policy old, Policy neu) {
        if (openness(neu.preset()) > openness(old.preset())) return "preset " + old.preset().id() + " → " + neu.preset().id();
        if (openness(neu.identity()) > openness(old.identity())) return "identity " + old.identity() + " → " + neu.identity();
        for (UUID o : neu.owners()) if (!old.owners().contains(o)) return "a new owner " + o;
        // Removing an owner narrows rights but takes control away from a person: never on the agent's own initiative.
        for (UUID o : old.owners()) if (!neu.owners().contains(o)) return "removing owner " + o;
        Set<String> agents = new LinkedHashSet<>(old.agents().keySet());
        agents.addAll(neu.agents().keySet());
        agents.add("gm");
        for (String a : agents) {
            if (neu.ceiling(a).compareTo(old.ceiling(a)) > 0) return "agent " + a + " ceiling " + old.ceiling(a).id() + " → " + neu.ceiling(a).id();
            if (neu.floor(a).compareTo(old.floor(a)) > 0) return "agent " + a + " floor " + old.floor(a).id() + " → " + neu.floor(a).id();
        }
        for (Map.Entry<String, Policy.Zone> e : old.zones().entrySet()) {
            Policy.Zone was = e.getValue(), now = neu.zones().get(e.getKey());
            if (was.deny().isEmpty()) continue;
            if (now == null || !now.deny().containsAll(was.deny()) || !sameBox(was, now)) return "zone " + e.getKey() + " denies less";
        }
        for (Map.Entry<String, Policy.Zone> e : neu.zones().entrySet()) {
            Policy.Zone now = e.getValue(), was = old.zones().get(e.getKey());
            if (now.agentMay().isEmpty()) continue;
            if (was == null || !was.agentMay().containsAll(now.agentMay()) || !sameBox(was, now)) return "zone " + e.getKey() + " lets the agent do more";
        }
        if (!old.safety().askAdmin().containsAll(neu.safety().askAdmin())) return "more kinds may reach admins (ask_admin)";
        if (!neu.safety().confirmFirst().containsAll(old.safety().confirmFirst())) return "fewer kinds need confirmation (confirm_first)";
        if (neu.limits().buildBlocks() > old.limits().buildBlocks() || neu.limits().giveItems() > old.limits().giveItems()) return "larger quotas";
        return null;
    }

    private static boolean sameBox(Policy.Zone a, Policy.Zone b) {
        return a.dim().equals(b.dim()) && java.util.Arrays.equals(a.from(), b.from()) && java.util.Arrays.equals(a.to(), b.to());
    }

    /** A change only the console may make (plan 4.10): an agent raised to an owner ceiling. Null if none. */
    public static String hostLevel(Policy old, Policy neu) {
        Set<String> agents = new LinkedHashSet<>(old.agents().keySet());
        agents.addAll(neu.agents().keySet());
        agents.add("gm");
        for (String a : agents) {
            if (neu.ceiling(a) == Role.OWNER && old.ceiling(a) != Role.OWNER) return "raising agent " + a + " to an owner ceiling";
        }
        return null;
    }
}
