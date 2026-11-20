package com.example.aiplayer.bot;

import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

/** Crafts {@code count} of an item from the inventory using the real recipes (needs a crafting table nearby for 3x3). */
public class CraftTask implements Task {
    private final String item;
    private final int count;

    public CraftTask(String item, int count) {
        this.item = item.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        this.count = Math.max(1, Math.min(count, 256));
    }

    @Override
    public String describe() {
        return "craft " + item + " x" + count;
    }

    static boolean blockNearby(ServerPlayerEntity p, java.util.function.Predicate<net.minecraft.block.BlockState> f, int r) {
        BlockPos c = p.getBlockPos();
        for (BlockPos q : BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r)))
            if (f.test(p.getWorld().getBlockState(q))) return true;
        return false;
    }

    @Override
    public boolean tick(AiBot bot) {
        ServerPlayerEntity p = bot.player;
        ServerWorld w = p.getServerWorld();
        Item out = Registries.ITEM.get(new Identifier("minecraft", item));
        if (out == net.minecraft.item.Items.AIR) {
            bot.note("FAILED craft: unknown item '" + item + "' (use ids like oak_planks, stick, wooden_pickaxe)");
            return true;
        }
        boolean table = blockNearby(p, s -> s.isOf(Blocks.CRAFTING_TABLE), 5);
        String lastProblem = "no crafting recipe for " + item;
        int made = 0;
        for (CraftingRecipe r : w.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
            ItemStack result = r.getOutput(w.getRegistryManager());
            if (!result.isOf(out)) continue;
            if (!r.fits(2, 2) && !table) {
                lastProblem = "needs a crafting table within 5 blocks (place one: craft crafting_table, then place it)";
                continue;
            }
            while (made < count) {
                String missing = tryOnce(p, r);
                if (missing != null) {
                    lastProblem = "missing " + missing;
                    break;
                }
                p.getInventory().offerOrDrop(result.copy());
                made += result.getCount();
            }
            if (made >= count) break;
        }
        if (made >= count) bot.note("DONE craft " + item + " x" + made);
        else if (made > 0) bot.note("PARTIAL craft " + item + " x" + made + " (" + lastProblem + ")");
        else bot.note("FAILED craft " + item + ": " + lastProblem);
        return true;
    }

    /** Removes the ingredients of one craft. Returns null on success or a description of what is missing. */
    private static String tryOnce(ServerPlayerEntity p, CraftingRecipe r) {
        Inventory inv = p.getInventory();
        int[] used = new int[inv.size()];
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            boolean ok = false;
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isEmpty() && ing.test(s) && s.getCount() - used[i] > 0) {
                    used[i]++;
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                ItemStack[] m = ing.getMatchingStacks();
                return m.length > 0 ? m[0].getItem().getName().getString() : "an ingredient";
            }
        }
        for (int i = 0; i < used.length; i++) if (used[i] > 0) inv.getStack(i).decrement(used[i]);
        return null;
    }
}
