package ua.zentix.almighty.bot;

import com.google.gson.JsonObject;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.almighty.world.Observe;
import ua.zentix.almighty.world.Rays;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bot keyboard and mouse, as on the client: held keys, clicks, mining, use and walking. The same logic as
 * {@code Minecraft.handleKeybinds}, {@code MultiPlayerGameMode} and {@code LocalPlayer.aiStep}; sends the same
 * packets as a client. Where the client needs a result (whether interaction moved to the offhand), use the same
 * server path as the packet handler, including its checks (reach, world border, building permission).
 * The server computes player physics itself ({@code doTick}); only real clients compute it on the client.
 * Server thread.
 */
final class Controls {
    enum Key {
        FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE;

        static Key parse(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the client crosshair can pick ({@code GameRenderer.pick}): nonspectators that are pickable by a ray. */
    static final java.util.function.Predicate<Entity> PICKABLE = e -> !e.isSpectator() && e.isPickable();

    private final Bot bot;
    private final Set<Key> held = EnumSet.noneOf(Key.class);
    /**
     * Timed keys: the tick from which a key releases itself. Rule autopilots hold keys this way and renew them
     * each run: if the rule fails or turns off, controls return to zero instead of holding the last input.
     * A rule's step runs on the next bot tick, after {@link #begin}: renewal every tick needs a duration of at least 2.
     */
    private final EnumMap<Key, Long> until = new EnumMap<>(Key.class);
    private int missTime, rightClickDelay;
    private boolean destroying;
    private BlockPos destroyPos = BlockPos.ZERO;
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float destroyProgress;
    private int destroyDelay;
    private int sequence;
    private boolean shiftSent, attackedThisTick;
    /** Movement packets the client would send after its ticks: the server recalculates visible entities for each. */
    private final MovePackets movePackets = new MovePackets();
    /** Blocks finished by holding attack since the last read (step result). */
    private int broken;

    Controls(Bot bot) {
        this.bot = bot;
    }

    Set<Key> held() {
        return held;
    }

    /** Remaining ticks for timed keys; indefinite keys are omitted. */
    EnumMap<Key, Long> leases() {
        EnumMap<Key, Long> left = new EnumMap<>(Key.class);
        long now = bot.tickNow();
        until.forEach((k, t) -> left.put(k, Math.max(0, t - now)));
        return left;
    }

    int takeBroken() {
        int b = broken;
        broken = 0;
        return b;
    }

    /** Client tick start: counters; releasing use stops item use (a drawn bow fires). */
    void begin(ServerPlayer p) {
        long now = bot.tickNow();
        for (Key key : Key.values()) {
            Long t = until.get(key);
            if (t != null && now >= t) release(p, key);
        }
        attackedThisTick = false;
        if (missTime > 0) missTime--;
        if (rightClickDelay > 0) rightClickDelay--;
        if (p.isUsingItem() && !held.contains(Key.USE)) bot.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
    }

    /**
     * Hold a key for {@code ticks} ticks (0 means until released): a timed key releases itself at the start of
     * the tick {@code ticks} later. An already held key is extended (or held indefinitely) without another click.
     */
    void hold(ServerPlayer p, Key key, int ticks, JsonObject out) {
        press(p, key, out);
        if (ticks > 0) until.put(key, bot.tickNow() + ticks);
        else until.remove(key);
    }

    /** Press a key. A mouse button press clicks immediately, as on the client; click result goes in {@code out}. */
    void press(ServerPlayer p, Key key, JsonObject out) {
        if (!held.add(key)) return;
        if (key == Key.ATTACK) out.add("attack", attack(p));
        else if (key == Key.USE) out.add("use", use(p));
    }

    void release(ServerPlayer p, Key key) {
        until.remove(key);
        if (!held.remove(key)) return;
        if (key == Key.ATTACK) stopDestroyBlock();
    }

    void releaseAll(ServerPlayer p) {
        for (Key key : Key.values()) release(p, key);
    }

    /** Left click: attack an entity, start mining, or swing at air. */
    JsonObject attack(ServerPlayer p) {
        JsonObject out = new JsonObject();
        if (p.isDeadOrDying()) return note(out, "the bot is dead");
        if (missTime > 0) return note(out, "cooldown after a miss");
        if (p.isUsingItem()) return note(out, "hands busy: an item is in use");
        if (!p.getMainHandItem().isItemEnabled(p.level().enabledFeatures())) return note(out, "the item is disabled on the server");
        HitResult hit = pick(p);
        describe(p, hit, out);
        switch (hit.getType()) {
            case ENTITY -> bot.send(ServerboundInteractPacket.createAttackPacket(((EntityHitResult) hit).getEntity(), p.isShiftKeyDown()));
            case BLOCK -> {
                BlockHitResult block = (BlockHitResult) hit;
                if (!p.level().getBlockState(block.getBlockPos()).isAir()) {
                    startDestroyBlock(p, block.getBlockPos(), block.getDirection());
                    if (p.level().getBlockState(block.getBlockPos()).isAir()) {
                        attackedThisTick = true;
                        out.addProperty("broken", true);
                    }
                } else miss(p);
            }
            case MISS -> miss(p);
        }
        bot.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        return out;
    }

    private void miss(ServerPlayer p) {
        if (p.gameMode.getGameModeForPlayer().isSurvival()) missTime = 10;
    }

    /** Right click: entity, block, item; main hand, then offhand, as on the client. */
    JsonObject use(ServerPlayer p) {
        JsonObject out = new JsonObject();
        if (p.isDeadOrDying()) return note(out, "the bot is dead");
        if (destroying) return note(out, "already breaking a block");
        rightClickDelay = 4;
        if (p.isUsingItem()) return note(out, "hands busy: an item is in use");
        HitResult hit = pick(p);
        describe(p, hit, out);
        ServerLevel level = p.serverLevel();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = p.getItemInHand(hand);
            if (!stack.isItemEnabled(level.enabledFeatures())) return out;
            if (hit instanceof EntityHitResult entityHit) {
                Entity entity = entityHit.getEntity();
                if (!level.getWorldBorder().isWithinBounds(entity.blockPosition())) return out;
                InteractionResult r = interact(p, entity, hand, entityHit.getLocation().subtract(entity.position()));
                if (!r.consumesAction()) r = interact(p, entity, hand, null);
                if (r.consumesAction()) return result(out, hand, r);
            } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                InteractionResult r = useOn(p, hand, blockHit);
                if (r.consumesAction() || r == InteractionResult.FAIL) return result(out, hand, r);
            }
            if (!stack.isEmpty()) {
                InteractionResult r = useItem(p, hand);
                if (r.consumesAction()) {
                    out.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    return result(out, hand, r);
                }
            }
        }
        out.addProperty("result", "pass");
        return out;
    }

    /** Client tick end: held buttons (repeat use, mining) and walking; then player physics. */
    void end(ServerPlayer p) {
        if (held.contains(Key.USE) && rightClickDelay == 0 && !p.isUsingItem()) use(p);
        continueAttack(p, !attackedThisTick && held.contains(Key.ATTACK));
        move(p);
    }

    private void continueAttack(ServerPlayer p, boolean leftClick) {
        if (!leftClick) missTime = 0;
        if (missTime > 0 || p.isUsingItem()) return;
        HitResult hit = leftClick ? pick(p) : null;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && !p.level().getBlockState(block.getBlockPos()).isAir()) {
            if (continueDestroyBlock(p, block.getBlockPos(), block.getDirection())) bot.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        } else {
            stopDestroyBlock();
        }
    }

    private boolean startDestroyBlock(ServerPlayer p, BlockPos pos, Direction face) {
        GameType mode = p.gameMode.getGameModeForPlayer();
        if (p.blockActionRestricted(p.level(), pos, mode) || !p.level().getWorldBorder().isWithinBounds(pos)) return false;
        if (mode.isCreative()) {
            action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
            destroyDelay = 5;
        } else if (!destroying || !sameTarget(p, pos)) {
            if (destroying) bot.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroyPos, face));
            BlockState state = p.level().getBlockState(pos);
            // Like the client: compute speed before the packet; the server may break a block immediately on START.
            boolean instant = !state.isAir() && state.getDestroyProgress(p, p.level(), pos) >= 1.0F;
            action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
            if (!instant) {
                destroying = true;
                destroyPos = pos.immutable();
                destroyingItem = p.getMainHandItem().copy();
                destroyProgress = 0;
            }
        }
        return true;
    }

    private boolean continueDestroyBlock(ServerPlayer p, BlockPos pos, Direction face) {
        if (destroyDelay > 0) {
            destroyDelay--;
            return true;
        }
        if (p.gameMode.getGameModeForPlayer().isCreative() && p.level().getWorldBorder().isWithinBounds(pos)) {
            destroyDelay = 5;
            action(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
            broken++;
            return true;
        }
        if (!sameTarget(p, pos)) return startDestroyBlock(p, pos, face);
        BlockState state = p.level().getBlockState(pos);
        if (state.isAir()) {
            destroying = false;
            return false;
        }
        destroyProgress += state.getDestroyProgress(p, p.level(), pos);
        if (destroyProgress >= 1.0F) {
            destroying = false;
            action(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face);
            destroyProgress = 0;
            destroyDelay = 5;
            broken++;
        }
        return true;
    }

    private void stopDestroyBlock() {
        if (!destroying) return;
        bot.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroyPos, Direction.DOWN));
        destroying = false;
        destroyProgress = 0;
    }

    private boolean sameTarget(ServerPlayer p, BlockPos pos) {
        return pos.equals(destroyPos) && !destroyingItem.shouldCauseBlockBreakReset(p.getMainHandItem());
    }

    private void action(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        bot.send(new ServerboundPlayerActionPacket(action, pos, face, ++sequence));
    }

    /**
     * Entity interaction follows {@code handleInteract}: world border, reach, NeoForge event, entity itself,
     * advancement criterion, swing. {@code at} is a point on the entity (as tried first by the client); null means no point.
     */
    private static InteractionResult interact(ServerPlayer p, Entity entity, InteractionHand hand, Vec3 at) {
        ServerLevel level = p.serverLevel();
        if (!level.getWorldBorder().isWithinBounds(entity.blockPosition()) || !p.canInteractWithEntity(entity.getBoundingBox(), 1.0)) return InteractionResult.PASS;
        ItemStack stack = p.getItemInHand(hand);
        if (!stack.isItemEnabled(level.enabledFeatures())) return InteractionResult.PASS;
        ItemStack before = stack.copy();
        InteractionResult r;
        if (at != null) {
            r = net.neoforged.neoforge.common.CommonHooks.onInteractEntityAt(p, entity, at, hand);
            if (r == null) r = entity.interactAt(p, at, hand);
        } else {
            r = p.interactOn(entity, hand);
        }
        if (r.consumesAction()) {
            CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY.trigger(p, r.indicateItemUse() ? before : ItemStack.EMPTY, entity);
            if (r.shouldSwing()) p.swing(hand, true);
        }
        return r;
    }

    /** Use on a block follows {@code handleUseItemOn}: reach, point within the block, height, building permission. */
    private static InteractionResult useOn(ServerPlayer p, InteractionHand hand, BlockHitResult hit) {
        ServerLevel level = p.serverLevel();
        ItemStack stack = p.getItemInHand(hand);
        BlockPos pos = hit.getBlockPos();
        if (!level.getWorldBorder().isWithinBounds(pos)) return InteractionResult.FAIL;
        if (!p.canInteractWithBlock(pos, 1.0)) return InteractionResult.PASS;
        Vec3 inside = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        if (Math.abs(inside.x) >= 1.0000001 || Math.abs(inside.y) >= 1.0000001 || Math.abs(inside.z) >= 1.0000001) return InteractionResult.PASS;
        int top = level.getMaxBuildHeight();
        if (pos.getY() >= top) {
            p.sendSystemMessage(Component.translatable("build.tooHigh", top - 1).withStyle(ChatFormatting.RED), true);
            return InteractionResult.PASS;
        }
        if (!level.mayInteract(p, pos)) return InteractionResult.PASS;
        InteractionResult r = p.gameMode.useItemOn(p, level, stack, hand, hit);
        if (r.consumesAction()) CriteriaTriggers.ANY_BLOCK_USE.trigger(p, pos, stack.copy());
        if (hit.getDirection() == Direction.UP && !r.consumesAction() && pos.getY() >= top - 1 && stack.getItem() instanceof BlockItem) {
            p.sendSystemMessage(Component.translatable("build.tooHigh", top - 1).withStyle(ChatFormatting.RED), true);
        } else if (r.shouldSwing()) {
            p.swing(hand, true);
        }
        return r;
    }

    /** Use an item in hand follows {@code handleUseItem}. */
    private static InteractionResult useItem(ServerPlayer p, InteractionHand hand) {
        ItemStack stack = p.getItemInHand(hand);
        if (stack.isEmpty() || !stack.isItemEnabled(p.level().enabledFeatures())) return InteractionResult.PASS;
        InteractionResult r = p.gameMode.useItem(p, p.serverLevel(), stack, hand);
        if (r.shouldSwing()) p.swing(hand, true);
        return r;
    }

    /**
     * Walking as in {@code LocalPlayer.aiStep}: key impulses (sneaking and item use slow movement), sprinting,
     * sneaking, flying up/down; when riding, vehicle input packets and client-side vehicle actions ({@link Vehicles}).
     * Then server player physics ({@code doTick}) and server movement-packet handling: surrounding chunks and visible
     * entities when a client would send a packet ({@link MovePackets}), fall damage, player movement (used by
     * projectiles: {@code getKnownMovement}), walking statistics and hunger. Physics does not move a rider:
     * its position belongs to the vehicle.
     */
    private void move(ServerPlayer p) {
        float forward = impulse(Key.FORWARD, Key.BACK), strafe = impulse(Key.LEFT, Key.RIGHT);
        boolean sneak = held.contains(Key.SNEAK), jump = held.contains(Key.JUMP);
        if (p.isCrouching() || p.isVisuallyCrawling()) {
            float f = (float) p.getAttributeValue(Attributes.SNEAKING_SPEED);
            forward *= f;
            strafe *= f;
        }
        if (p.isUsingItem() && !p.isPassenger()) {
            forward *= 0.2F;
            strafe *= 0.2F;
        }
        if (sneak != shiftSent) {
            bot.send(new ServerboundPlayerCommandPacket(p, sneak ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            shiftSent = sneak;
        }
        sprint(p, forward);
        // A client does not tick the player until the chunk below arrives (LocalPlayer.tick); the bot waits for chunks
        // its tick will touch: player physics reads blocks and fluids underfoot, and an unready chunk would load synchronously.
        if (!p.isPassenger() && !ready(p)) {
            // The server learns the teleported position from the first movement packet, then every 20 ticks, as on the client.
            if (movePackets.tick(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround())) {
                p.serverLevel().getChunkSource().move(p);
            }
            return;
        }
        if (p.isPassenger()) bot.send(new ServerboundPlayerInputPacket(strafe, forward, jump, sneak));
        bot.vehicles.tick(p, held);
        if (!p.isPassenger()) {
            p.xxa = strafe;
            p.zza = forward;
            p.setJumping(jump);
            if (p.getAbilities().flying) {
                int dir = (jump ? 1 : 0) - (sneak ? 1 : 0);
                if (dir != 0) p.setDeltaMovement(p.getDeltaMovement().add(0, dir * p.getAbilities().getFlyingSpeed() * 3.0F, 0));
            }
        }
        boolean riding = p.isPassenger();
        // Rider: the client starts its tick with zero velocity and seats it (rideTick); after player physics, the server
        // restores its tick-start position (absMoveTo to firstGood...). Otherwise a bot whose vehicle the world did not tick
        // (chunk outside simulation distance) would fall out of place while still riding.
        if (riding) p.setDeltaMovement(Vec3.ZERO);
        Vec3 before = p.position();
        p.doTick();
        if (p.isRemoved()) return;
        if (riding && p.isPassenger()) p.absMoveTo(before.x, before.y, before.z, p.getYRot(), p.getXRot());
        Vec3 d = p.position().subtract(before);
        // A rider sends a packet every tick; a walking player sends one after movement, rotation, landing or leaving ground,
        // and every 20 ticks when stationary.
        if (riding || movePackets.tick(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround())) {
            p.serverLevel().getChunkSource().move(p);
        }
        if (!riding && !p.isPassenger()) {
            p.doCheckFallDamage(d.x, d.y, d.z, p.onGround());
            p.setKnownMovement(d);
            p.checkMovementStatistics(d.x, d.y, d.z);
        }
    }

    /** All chunks around the bot's (vehicle's) box are ready, with a one-block margin and per-tick velocity ({@code getChunkNow}). */
    static boolean ready(Entity p) {
        AABB box = p.getBoundingBox().expandTowards(p.getDeltaMovement()).inflate(1.0);
        ServerChunkCache chunks = ((ServerLevel) p.level()).getChunkSource();
        for (int cx = Mth.floor(box.minX) >> 4; cx <= Mth.floor(box.maxX) >> 4; cx++) {
            for (int cz = Mth.floor(box.minZ) >> 4; cz <= Mth.floor(box.maxZ) >> 4; cz++) {
                if (chunks.getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    private float impulse(Key plus, Key minus) {
        boolean a = held.contains(plus), b = held.contains(minus);
        return a == b ? 0 : a ? 1 : -1;
    }

    /** Sprinting: held while the key is pressed and moving forward, with enough food, no blindness and no wall collision. */
    private void sprint(ServerPlayer p, float forward) {
        boolean able = (p.getFoodData().getFoodLevel() > 6 || p.mayFly()) && !p.isUsingItem()
                && !p.hasEffect(MobEffects.BLINDNESS) && !p.isFallFlying() && !p.isPassenger();
        boolean want = held.contains(Key.SPRINT) && forward >= 0.8F && !p.isShiftKeyDown() && !(p.horizontalCollision && !p.minorHorizontalCollision);
        if (want && able) {
            if (!p.isSprinting()) bot.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        } else if (p.isSprinting() && !p.isSwimming()) {
            bot.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
    }

    /**
     * Crosshair target as in {@code GameRenderer.pick}: block (shape, transparent fluids) within block interaction
     * reach, entity within entity reach, whichever is nearer; beyond reach is a miss. No chunk loading.
     */
    static HitResult pick(ServerPlayer p) {
        double blockRange = p.blockInteractionRange(), entityRange = p.entityInteractionRange();
        double range = Math.max(blockRange, entityRange), rangeSqr = range * range;
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1.0F);
        Rays.Hit ray = Rays.clip(p.serverLevel(), eye, eye.add(look.scale(range)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p);
        HitResult hit = ray.block();
        double blockDistSqr = hit.getLocation().distanceToSqr(eye);
        if (hit.getType() != HitResult.Type.MISS) {
            rangeSqr = blockDistSqr;
            range = Math.sqrt(rangeSqr);
        }
        Vec3 end = eye.add(look.scale(range));
        AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(p, eye, end, box, PICKABLE, rangeSqr);
        if (entity != null && entity.getLocation().distanceToSqr(eye) < blockDistSqr) return within(entity, eye, entityRange);
        return within(hit, eye, blockRange);
    }

    private static HitResult within(HitResult hit, Vec3 eye, double range) {
        Vec3 at = hit.getLocation();
        if (at.closerThan(eye, range)) return hit;
        return BlockHitResult.miss(at, Direction.getNearest(at.x - eye.x, at.y - eye.y, at.z - eye.z), BlockPos.containing(at));
    }

    /** Click target for the step result. */
    static void describe(ServerPlayer p, HitResult hit, JsonObject out) {
        if (hit instanceof EntityHitResult e) {
            JsonObject t = new JsonObject();
            t.addProperty("entity", EntityType.getKey(e.getEntity().getType()).toString());
            t.addProperty("name", e.getEntity().getName().getString());
            t.addProperty("uuid", e.getEntity().getUUID().toString());
            out.add("target", t);
        } else if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            JsonObject t = new JsonObject();
            t.addProperty("block", BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(b.getBlockPos()).getBlock()).toString());
            t.add("pos", Observe.pos(b.getBlockPos()));
            t.addProperty("face", b.getDirection().getSerializedName());
            out.add("target", t);
        } else {
            out.addProperty("target", "nothing within reach");
        }
    }

    private static JsonObject result(JsonObject out, InteractionHand hand, InteractionResult r) {
        out.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
        out.addProperty("result", r.name().toLowerCase(Locale.ROOT));
        return out;
    }

    private static JsonObject note(JsonObject out, String why) {
        out.addProperty("ignored", why);
        return out;
    }
}
