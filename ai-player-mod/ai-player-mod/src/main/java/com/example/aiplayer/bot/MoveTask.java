package com.example.aiplayer.bot;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Walk to within {@code reach} blocks of a position. Movement is stepped (no physics) along an A* path. */
public class MoveTask implements Task {
    private static final double SPEED = 0.23; // blocks per tick, a bit above sprinting

    private final BlockPos target;
    private final double reach;
    private final String label;
    private List<BlockPos> path;
    private int idx = 0;
    private int stuck = 0;

    public MoveTask(BlockPos target, double reach, String label) {
        this.target = target;
        this.reach = reach;
        this.label = label;
    }

    @Override
    public String describe() {
        return "move to " + label + " (" + target.getX() + "," + target.getY() + "," + target.getZ() + ")";
    }

    @Override
    public boolean tick(AiBot bot) {
        ServerPlayerEntity p = bot.player;
        Vec3d here = p.getPos();
        if (here.squaredDistanceTo(Vec3d.ofCenter(target)) <= reach * reach) return true;

        if (path == null) {
            path = Pathfinder.find(p.getWorld(), p.getBlockPos(), target, reach);
            if (path.isEmpty()) {
                bot.note("FAILED move: no path to " + label + " " + target.toShortString());
                return true;
            }
        }
        if (idx >= path.size()) { // arrived at the end of the path
            return true;
        }
        BlockPos next = path.get(idx);
        Vec3d goal = new Vec3d(next.getX() + 0.5, next.getY(), next.getZ() + 0.5);
        Vec3d d = goal.subtract(here);
        double len = d.length();
        if (len <= SPEED) {
            bot.moveTo(goal);
            idx++;
            stuck = 0;
        } else {
            Vec3d step = here.add(d.multiply(SPEED / len));
            bot.moveTo(step);
            bot.lookAt(new Vec3d(goal.x, here.y + 1.62, goal.z));
            if (++stuck > 400) {
                bot.note("FAILED move: got stuck on the way to " + label);
                return true;
            }
        }
        return false;
    }
}
