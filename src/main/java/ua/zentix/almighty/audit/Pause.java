package ua.zentix.almighty.audit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The owner's red button (plan 4.11, A3): {@code /almighty pause [agent]}. A paused agent (or every agent) is refused
 * any call with effects (reading stays allowed, so it can still look and explain), its jobs freeze in the queue and
 * its bots stop. Lasts until {@code /almighty resume} or a restart. Thread-safe.
 */
public final class Pause {
    private volatile boolean all;
    private final Set<String> agents = ConcurrentHashMap.newKeySet();

    public void pause(String agent) {
        if (agent == null) all = true;
        else agents.add(agent);
    }

    /** Resume one agent, or (null) everything. */
    public void resume(String agent) {
        if (agent == null) {
            all = false;
            agents.clear();
        } else {
            agents.remove(agent);
        }
    }

    public boolean paused(String agent) {
        return all || agents.contains(agent);
    }

    /** Whether this agent itself was paused (not counting pause of all). */
    public boolean pausedAlone(String agent) {
        return agents.contains(agent);
    }

    public boolean all() {
        return all;
    }

    public Set<String> agents() {
        return Set.copyOf(agents);
    }
}
