package com.example.aiplayer.bot;

import com.example.aiplayer.AiPlayerMod;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** Find the nearest block of a kind, walk there, break it, pick up the drops. Repeat {@code count} times. */
public class MineTask implements Task {
    private enum Phase { FIND, MOVE, BREAK, WAIT_DROP, PICKUP }

    private final String name;
    private final int count;
    private int broken = 0;
    private Phase phase = Phase.FIND;
    private BlockPos target;
    private Task sub;
    private int ticks, needed;
    private final Set<BlockPos> skip = new HashSet<>();

    public MineTask(String name, int count) {
        this.name = name.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        this.count = Math.max(1, Math.min(count, 64));
    }

    @Override
    public String describe() {
        return "mine " + name + " (" + broken + "/" + count + ")";
    }

    static boolean matches(String want, String id) {
        if (id.equals(want)) return true;
        return (want.equals("log") || want.equals("ore") || want.equals("planks") || want.equals("leaves")
                || want.equals("wool") || want.equals("stone_bricks")) && id.endsWith("_" + want);
    }

    @Override
    public boolean tick(AiBot bot) {
        ServerPlayerEntity p = bot.player;
        ServerWorld w = p.getServerWorld();
        switch (phase) {
            case FIND -> {
                if (broken >= count) {
                    bot.note("DONE mine " + name + " x" + broken);
                    return true;
                }
                target = findNearest(bot, w);
                if (target == null) {
                    bot.note((broken > 0 ? "PARTIAL " : "FAILED ") + "mine " + name + ": broke " + broken
                            + ", no more reachable '" + name + "' within " + AiPlayerMod.config.scanRadius + " blocks. Explore elsewhere.");
                    return true;
                }
                sub = new MoveTask(target, 3.6, name);
                phase = Phase.MOVE;
            }
            case MOVE -> {
                if (sub.tick(bot)) {
                    if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > 5.0 * 5.0) {
                        skip.add(target);
                        phase = Phase.FIND;
                        return false;
                    }
                    BlockState st = w.getBlockState(target);
                    bot.equipBestTool(st);
                    float hardness = st.getHardness(w, target);
                    var stack = p.getMainHandStack();
                    float speed = Math.max(1f, stack.getMiningSpeedMultiplier(st));
                    boolean harvest = !st.isToolRequired() || stack.isSuitableFor(st);
                    needed = Math.max(1, Math.min(200, Math.round(hardness * (harvest ? 30f : 100f) / speed)));
                    ticks = 0;
                    phase = Phase.BREAK;
                }
            }
            case BREAK -> {
                bot.lookAt(Vec3d.ofCenter(target));
                if (ticks % 6 == 0) p.swingHand(Hand.MAIN_HAND, true);
                if (++ticks >= needed) {
                    if (p.interactionManager.tryBreakBlock(target)) {
                        broken++;
                    } else {
                        skip.add(target);
                    }
                    ticks = 0;
                    phase = Phase.WAIT_DROP;
                }
            }
            case WAIT_DROP -> {
                if (++ticks >= 6) {
                    ticks = 0;
                    phase = Phase.PICKUP;
                    sub = nextPickup(bot, w);
                }
            }
            case PICKUP -> {
                if (sub == null || sub.tick(bot) || ++ticks > 200) {
                    sub = nextPickup(bot, w);
                    if (sub == null) phase = Phase.FIND;
                }
            }
        }
        return false;
    }

    static Task nextPickup(AiBot bot, ServerWorld w) {
        Vec3d pos = bot.player.getPos();
        List<ItemEntity> items = w.getEntitiesByClass(ItemEntity.class, new Box(pos, pos).expand(8),
                e -> e.isAlive() && e.getPos().squaredDistanceTo(pos) > 1.2);
        if (items.isEmpty()) return null;
        items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(pos)));
        return new MoveTask(items.get(0).getBlockPos(), 1.3, "dropped item");
    }

    private BlockPos findNearest(AiBot bot, ServerWorld w) {
        BlockPos c = bot.player.getBlockPos();
        int r = AiPlayerMod.config.scanRadius;
        boolean xray = AiPlayerMod.config.xray;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    double d = dx * dx + dy * dy * 1.5 + dz * dz;
                    if (d >= bestD || skip.contains(m)) continue;
                    BlockState s = w.getBlockState(m);
                    if (s.isAir() || !matches(name, Registries.BLOCK.getId(s.getBlock()).getPath())) continue;
                    if (s.getHardness(w, m) < 0) continue;
                    if (!xray && !exposed(w, m)) continue;
                    best = m.toImmutable();
                    bestD = d;
                }
        return best;
    }

    private static boolean exposed(ServerWorld w, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockState s = w.getBlockState(p.offset(d));
            if (s.isAir() || s.getCollisionShape(w, p.offset(d)).isEmpty()) return true;
        }
        return false;
    }
}
