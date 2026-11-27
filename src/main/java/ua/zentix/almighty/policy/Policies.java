package ua.zentix.almighty.policy;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The live policy: {@code config/almighty/policy.toml}, written with comments on first start, reloaded with
 * {@code /almighty reload}. A file that does not parse keeps the last good policy and reports why (plan 4.5).
 * Thread-safe: the bridge reads {@link #current()} without locking.
 */
public final class Policies {
    private static final Logger LOG = LogUtils.getLogger();

    private final Path file;
    private volatile Policy current = Policy.defaults();
    private volatile String error;

    public Policies(Path file) {
        this.file = file;
    }

    /** In-memory policy (tests). */
    public static Policies of(Policy policy) {
        Policies p = new Policies(null);
        p.current = policy;
        return p;
    }

    public Policy current() {
        return current;
    }

    /** Why the file was refused the last time it was read; null if it was fine. */
    public String error() {
        return error;
    }

    public Path file() {
        return file;
    }

    /**
     * First start: write the commented template with {@code preset} if there is no file. An existing install (its
     * token file predates this start) gets {@code private}, so a server that updates keeps its behaviour.
     */
    public void load(Policy.Preset preset) {
        if (file == null) return;
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Policy.template(preset), StandardCharsets.UTF_8);
                LOG.info("Almighty: wrote {} (preset {})", file, preset.id());
            }
        } catch (IOException e) {
            LOG.error("Almighty: cannot write {}: {}", file, e.toString());
        }
        String why = reload();
        if (why != null) {
            // No last good policy yet: never fall back to something wider than the owner may have chosen.
            set(Policy.lockdown());
            LOG.error("Almighty: {} refused; until it is fixed (/almighty reload) agents act as on a public server with no "
                    + "owners: {}", file, why);
        }
    }

    /** Bumped by every policy or role change: caches of rights (rules' creator checks) start over. */
    public static final java.util.concurrent.atomic.AtomicLong CHANGES = new java.util.concurrent.atomic.AtomicLong();

    /** Read the file again; null on success, else the reason (the previous policy stays). */
    public synchronized String reload() {
        CHANGES.incrementAndGet();
        if (file == null) return null;
        try {
            Policy p = Policy.parse(Files.readString(file, StandardCharsets.UTF_8));
            current = p;
            Identity.mode(p.identity());
            error = null;
            return null;
        } catch (IOException e) {
            error = "cannot read " + file + ": " + e.getMessage();
        } catch (Policy.Invalid e) {
            error = e.getMessage();
        }
        return error;
    }

    /** Replace the policy in memory (GameTest). */
    public void set(Policy p) {
        CHANGES.incrementAndGet();
        current = p;
        Identity.mode(p.identity());
    }
}
