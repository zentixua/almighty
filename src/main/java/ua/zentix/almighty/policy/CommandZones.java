package ua.zentix.almighty.policy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.world.phys.Vec3;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.world.Dims;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Zones for commands (plan 4.7): a command below owner is checked where it runs and at every coordinate it names,
 * parsed by vanilla's own dispatcher with the source it will run with. A command that changes blocks there
 * ({@code fill}, {@code setblock}, {@code clone}, {@code place}, {@code fillbiome}, block data and loot, {@code execute
 * store … block}) is checked as {@code build} over its whole box. A command that moves where it runs ({@code execute
 * as|at|positioned|in|…}) cannot be placed before it runs, so it is refused while any zone denies {@code build} or
 * {@code command}. Entity selectors are not checked (documented in the policy template and README).
 */
public final class CommandZones {
    private CommandZones() {}

    /** A zone check for one box ({@link Gate#zone} with the call's caller and parameters). */
    public interface Check {
        void zone(Node node, String dim, int[] lo, int[] hi) throws RpcException;
    }

    /** {@code execute} subcommands that change the source's position, dimension or entity. */
    static final Set<String> MOVES = Set.of("as", "at", "positioned", "in", "align", "anchored", "rotated", "facing", "on", "summon");
    static final Set<String> EDITS = Set.of("fill", "setblock", "clone", "place", "fillbiome", "summon", "loot");
    /** Commands that run other commands unseen (a datapack function's lines): refused while places are protected. */
    static final Set<String> UNSEEN = Set.of("function", "schedule");
    static final Set<String> WRITES = Set.of("modify", "merge", "remove", "replace", "insert", "store");
    /** How far {@code place} may reach from its position (jigsaw structures spread up to 128 blocks). */
    static final int PLACE_REACH = 128;

    /** Check one command (with or without {@code /}). Unparseable commands pass: vanilla refuses them when they run. */
    public static void check(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source, String command, Check check)
            throws RpcException {
        String text = command.startsWith("/") ? command.substring(1) : command;
        String dim = Dims.id(source.getLevel());
        Vec3 at = source.getPosition();
        int[] here = {(int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)};
        check.zone(Node.COMMAND, dim, here, here);
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(text, source);
        if (!parsed.getExceptions().isEmpty() || parsed.getReader().canRead()) return;
        List<String> literals = new ArrayList<>();
        List<int[]> points = new ArrayList<>();
        boolean inExecute = false, cloning = false;
        for (CommandContextBuilder<CommandSourceStack> c = parsed.getContext(); c != null; c = c.getChild()) {
            for (ParsedCommandNode<CommandSourceStack> n : c.getNodes()) {
                if (!(n.getNode() instanceof LiteralCommandNode<CommandSourceStack> l)) continue;
                String lit = l.getLiteral();
                if (lit.equals("execute")) inExecute = true;
                else if (lit.equals("run")) inExecute = false;
                else if (inExecute && MOVES.contains(lit)) {
                    throw new RpcException(403, "policy", "Not run: execute " + lit + " moves where the command runs, and zones "
                            + "or claims protect places on this server; run it without execute " + lit);
                }
                if (literals.isEmpty() && UNSEEN.contains(lit)) {
                    throw new RpcException(403, "policy", "Not run: " + lit + " runs commands that cannot be checked first, and zones or"
                            + " claims protect places on this server");
                }
                // clone from/to another dimension writes where this check does not look.
                if (lit.equals("clone")) cloning = true;
                else if (cloning && (lit.equals("from") || lit.equals("to"))) {
                    throw new RpcException(403, "policy", "Not run: clone across dimensions, while zones or claims protect places on this server");
                }
                if (!literals.isEmpty() && UNSEEN.contains(lit) && literals.get(literals.size() - 1).equals("run")) {
                    throw new RpcException(403, "policy", "Not run: " + lit + " runs commands that cannot be checked first, and zones or"
                            + " claims protect places on this server");
                }
                literals.add(lit);
            }
            for (ParsedArgument<CommandSourceStack, ?> a : c.getArguments().values()) {
                if (a.getResult() instanceof Coordinates co) {
                    Vec3 p = co.getPosition(source);
                    points.add(new int[]{(int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)});
                }
            }
        }
        for (int[] p : points) check.zone(Node.COMMAND, dim, p, p);
        boolean edits = literals.stream().anyMatch(EDITS::contains)
                || literals.contains("block") && literals.stream().anyMatch(WRITES::contains);
        if (!edits) return;
        if (points.isEmpty()) points.add(here);
        int[] lo = points.get(0).clone(), hi = points.get(0).clone();
        for (int[] p : points) grow(lo, hi, p);
        if (literals.contains("clone") && points.size() >= 3) {
            int[] a = points.get(0), b = points.get(1), d = points.get(2);
            grow(lo, hi, new int[]{d[0] + Math.abs(b[0] - a[0]), d[1] + Math.abs(b[1] - a[1]), d[2] + Math.abs(b[2] - a[2])});
        }
        if (literals.contains("place")) {
            for (int i = 0; i < 3; i++) {
                lo[i] -= PLACE_REACH;
                hi[i] += PLACE_REACH;
            }
        }
        check.zone(Node.BUILD, dim, lo, hi);
    }

    private static void grow(int[] lo, int[] hi, int[] p) {
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(lo[i], p[i]);
            hi[i] = Math.max(hi[i], p[i]);
        }
    }

    /** Function lines that cannot be checked before they run: macro lines and continued lines. Null if fine. */
    public static String unchecked(String line) {
        String t = line.strip();
        if (t.startsWith("$")) return "a macro line ($...) cannot be checked against zones or claims before it runs";
        if (t.endsWith("\\")) return "a continued line (\\) cannot be checked against zones or claims line by line";
        return null;
    }
}
