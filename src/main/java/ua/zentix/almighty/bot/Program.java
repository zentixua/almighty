package ua.zentix.almighty.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Bot program: steps from one {@code bot.act} call, running on bot ticks; instant steps run consecutively
 * within one tick (at most {@link #MAX_INSTANT}). A step error stops the program. Results contain one entry
 * for each completed step.
 */
final class Program {
    static final int MAX_INSTANT = 64;

    final long id;
    private final List<Steps.Step> steps;
    private final JsonArray results = new JsonArray();
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private JsonObject out = new JsonObject();
    private int index;
    private String state = "queued", error;
    /** The call already returned without waiting: completion emits {@code bot_done} to the feed. */
    volatile boolean detached;

    Program(long id, List<Steps.Step> steps) {
        this.id = id;
        this.steps = steps;
    }

    CompletableFuture<JsonObject> done() {
        return done;
    }

    /** Program tick; true means finished (all steps or an error). */
    boolean tick(Bot bot) {
        if (state.equals("cancelled")) return true;
        state = "running";
        int instant = 0;
        while (index < steps.size()) {
            ServerPlayer p = bot.player();
            boolean finished;
            try {
                finished = steps.get(index).tick(bot, p, out);
            } catch (Steps.Failed e) {
                out.addProperty("step", index);
                out.addProperty("error", e.getMessage());
                results.add(out);
                error = "step " + index + ": " + e.getMessage();
                state = "failed";
                return true;
            }
            // Removed mid-step (a rule triggered by this step replaced the programs).
            if (state.equals("cancelled")) return true;
            if (!finished) return false;
            out.addProperty("step", index);
            results.add(out);
            out = new JsonObject();
            index++;
            if (++instant >= MAX_INSTANT && index < steps.size()) return false;
        }
        state = "done";
        return true;
    }

    /** Remove the program (a new one with {@code replace}, bot left): the step releases resources it holds itself. */
    void cancel(Bot bot, String why) {
        if (index < steps.size() && state.equals("running")) {
            try {
                steps.get(index).cancel(bot, bot.player());
            } catch (RuntimeException e) {
                // Also removed during bot disconnection, when an error would crash the server tick; cancelAll releases the keys.
                error = why + " (the step failed to release what it held: " + e + ")";
                state = "cancelled";
                return;
            }
        }
        state = "cancelled";
        error = why;
    }

    void complete() {
        done.complete(describe());
    }

    JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("program", id);
        o.addProperty("state", state);
        o.addProperty("steps_done", index);
        o.addProperty("steps", steps.size());
        if (error != null) o.addProperty("error", error);
        o.add("results", results.deepCopy());
        return o;
    }
}
