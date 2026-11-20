package com.example.aiplayer.bot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/** Small A* over "standing positions" (feet block). Moves: walk, jump up 1, drop up to 3. */
public final class Pathfinder {
    private static final int MAX_NODES = 6000;
    private static final Set<Block> DANGER = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB,
            Blocks.WITHER_ROSE, Blocks.POWDER_SNOW);

    private Pathfinder() {}

    private record Node(BlockPos pos, double g, double f) {}

    /** Path to within {@code reach} blocks of {@code target} (center distance). Empty list = no path. */
    public static List<BlockPos> find(World w, BlockPos start, BlockPos target, double reach) {
        double reach2 = reach * reach;
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<BlockPos, BlockPos> from = new HashMap<>();
        Map<BlockPos, Double> best = new HashMap<>();
        open.add(new Node(start, 0, h(start, target)));
        best.put(start, 0.0);
        int expanded = 0;

        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node n = open.poll();
            if (n.g > best.getOrDefault(n.pos, Double.MAX_VALUE)) continue;
            if (center(n.pos).squaredDistanceTo(center(target)) <= reach2 && !n.pos.equals(target)) {
                return rebuild(from, n.pos);
            }
            for (BlockPos nb : neighbours(w, n.pos)) {
                double g = n.g + 1 + (nb.getY() != n.pos.getY() ? 0.5 : 0);
                if (g < best.getOrDefault(nb, Double.MAX_VALUE)) {
                    best.put(nb, g);
                    from.put(nb, n.pos);
                    open.add(new Node(nb, g, g + h(nb, target)));
                }
            }
        }
        return List.of();
    }

    private static double h(BlockPos a, BlockPos b) {
        return Math.sqrt(a.getSquaredDistance(b));
    }

    private static net.minecraft.util.math.Vec3d center(BlockPos p) {
        return new net.minecraft.util.math.Vec3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    private static List<BlockPos> rebuild(Map<BlockPos, BlockPos> from, BlockPos end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        for (BlockPos p = end; p != null; p = from.get(p)) path.addFirst(p);
        if (!path.isEmpty()) path.removeFirst(); // drop the start tile
        return path;
    }

    private static List<BlockPos> neighbours(World w, BlockPos p) {
        List<BlockPos> out = new ArrayList<>(8);
        for (var d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos side = p.offset(d);
            // same level
            if (standable(w, side)) { out.add(side); continue; }
            // jump up one (needs 3 free blocks above start: head room for the jump)
            BlockPos up = side.up();
            if (free(w, p.up(2)) && standable(w, up)) { out.add(up); continue; }
            // drop down up to 3
            if (free(w, side) && free(w, side.up())) {
                for (int i = 1; i <= 3; i++) {
                    BlockPos dn = side.down(i);
                    if (!free(w, dn)) break;
                    if (standable(w, dn)) { out.add(dn); break; }
                }
            }
        }
        return out;
    }

    /** Feet and head free, floor below solid and harmless. */
    public static boolean standable(World w, BlockPos p) {
        if (!free(w, p) || !free(w, p.up())) return false;
        BlockState floor = w.getBlockState(p.down());
        if (DANGER.contains(floor.getBlock())) return false;
        return !floor.getCollisionShape(w, p.down()).isEmpty() && floor.getFluidState().isEmpty();
    }

    public static boolean free(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (DANGER.contains(s.getBlock())) return false;
        return s.getCollisionShape(w, p).isEmpty() && s.getFluidState().isEmpty();
    }
}
