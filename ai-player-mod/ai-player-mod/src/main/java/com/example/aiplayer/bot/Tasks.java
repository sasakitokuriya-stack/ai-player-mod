package com.example.aiplayer.bot;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/** Small tasks. */
public final class Tasks {
    private Tasks() {}

    /** Does something in a single tick. */
    public static class Instant implements Task {
        private final String desc;
        private final Consumer<AiBot> run;

        public Instant(String desc, Consumer<AiBot> run) {
            this.desc = desc;
            this.run = run;
        }

        @Override public String describe() { return desc; }

        @Override
        public boolean tick(AiBot bot) {
            run.accept(bot);
            return true;
        }
    }

    public static class Wait implements Task {
        private int left;

        public Wait(int ticks) { this.left = Math.max(1, Math.min(ticks, 1200)); }

        @Override public String describe() { return "wait " + left + " ticks"; }

        @Override public boolean tick(AiBot bot) { return --left <= 0; }
    }

    public static Item item(String id) {
        return Registries.ITEM.get(new Identifier("minecraft", id.toLowerCase(Locale.ROOT).replace("minecraft:", "")));
    }

    /** Walk next to a spot and place a block there (uses the real placement logic when a neighbour block exists). */
    public static class Place implements Task {
        private final String block;
        private final BlockPos pos;
        private Task move;

        public Place(String block, BlockPos pos) {
            this.block = block.toLowerCase(Locale.ROOT).replace("minecraft:", "");
            this.pos = pos;
        }

        @Override public String describe() { return "place " + block + " at " + pos.toShortString(); }

        @Override
        public boolean tick(AiBot bot) {
            ServerPlayerEntity p = bot.player;
            ServerWorld w = p.getServerWorld();
            Item it = item(block);
            if (!bot.hasItem(it)) {
                bot.note("FAILED place: you have no " + block);
                return true;
            }
            if (move == null) move = new MoveTask(pos, 3.5, "build spot");
            if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > 4.5 * 4.5) {
                if (move.tick(bot)) {
                    bot.note("FAILED place " + block + ": cannot reach " + pos.toShortString());
                    return true;
                }
                return false;
            }
            BlockState here = w.getBlockState(pos);
            if (!here.isAir() && !here.isReplaceable()) {
                bot.note("FAILED place " + block + ": " + pos.toShortString() + " is occupied by "
                        + Registries.BLOCK.getId(here.getBlock()).getPath());
                return true;
            }
            if (p.getBoundingBox().intersects(new net.minecraft.util.math.Box(pos))) {
                bot.note("FAILED place " + block + ": you are standing in that spot, choose another position");
                return true;
            }
            bot.equipItem(it);
            bot.lookAt(Vec3d.ofCenter(pos));
            for (Direction d : Direction.values()) {
                BlockPos against = pos.offset(d);
                if (!w.getBlockState(against).getCollisionShape(w, against).isEmpty()) {
                    Direction face = d.getOpposite();
                    Vec3d hit = Vec3d.ofCenter(against).add(Vec3d.of(face.getVector()).multiply(0.5));
                    p.interactionManager.interactBlock(p, w, p.getMainHandStack(), Hand.MAIN_HAND,
                            new BlockHitResult(hit, face, against, false));
                    p.swingHand(Hand.MAIN_HAND, true);
                    if (!w.getBlockState(pos).isAir()) {
                        bot.note("DONE place " + block + " at " + pos.toShortString());
                    } else {
                        bot.note("FAILED place " + block + " at " + pos.toShortString() + " (blocked)");
                    }
                    return true;
                }
            }
            bot.note("FAILED place " + block + ": no solid neighbour block to attach to at " + pos.toShortString());
            return true;
        }
    }

    /** Wander in a direction (north/south/east/west/any) to find new terrain. */
    public static class Explore implements Task {
        private static final Random RNG = new Random();
        private final String dir;
        private Task move;
        private int tries = 0;

        public Explore(String dir) { this.dir = dir == null ? "any" : dir.toLowerCase(Locale.ROOT); }

        @Override public String describe() { return "explore " + dir; }

        @Override
        public boolean tick(AiBot bot) {
            ServerPlayerEntity p = bot.player;
            ServerWorld w = p.getServerWorld();
            if (move == null) {
                if (tries++ >= 8) {
                    bot.note("FAILED explore: could not find a walkable way " + dir);
                    return true;
                }
                // angle: 0 = east (+x), 90 = south (+z); a little jitter so retries differ
                double angle = switch (dir) {
                    case "east" -> 0;
                    case "south" -> 90;
                    case "west" -> 180;
                    case "north" -> 270;
                    default -> RNG.nextDouble() * 360;
                };
                angle = Math.toRadians(angle + (RNG.nextDouble() - 0.5) * 50);
                int dist = 20 + RNG.nextInt(12);
                int x = p.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
                int z = p.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
                BlockPos top = w.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                if (Pathfinder.find(w, p.getBlockPos(), top, 2.0).isEmpty()) return false; // try another spot next tick
                move = new MoveTask(top, 2.0, "unexplored area");
            }
            if (move.tick(bot)) {
                bot.note("DONE explore " + dir + " - now at " + p.getBlockPos().toShortString());
                return true;
            }
            return false;
        }
    }

    public static ItemStack stackOf(ServerPlayerEntity p, Item it) {
        for (ItemStack s : p.getInventory().main) if (s.isOf(it)) return s;
        return ItemStack.EMPTY;
    }
}
