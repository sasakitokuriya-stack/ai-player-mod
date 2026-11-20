package com.example.aiplayer.bot;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Map;

/**
 * Smelts items when a furnace is within 5 blocks. Simplified: the items and fuel are taken from the
 * inventory, it waits 10 ticks per item (max 10 s), then the result lands in the inventory.
 */
public class SmeltTask implements Task {
    private final String item;
    private final int count;
    private int wait = -1;
    private ItemStack result = ItemStack.EMPTY;

    public SmeltTask(String item, int count) {
        this.item = item.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        this.count = Math.max(1, Math.min(count, 64));
    }

    @Override
    public String describe() {
        return "smelt " + item + " x" + count;
    }

    @Override
    public boolean tick(AiBot bot) {
        ServerPlayerEntity p = bot.player;
        if (wait >= 0) {
            if (--wait > 0) return false;
            p.getInventory().offerOrDrop(result);
            bot.note("DONE smelt " + item + " x" + result.getCount());
            return true;
        }
        ServerWorld w = p.getServerWorld();
        Item out = Registries.ITEM.get(new Identifier("minecraft", item));
        if (!CraftTask.blockNearby(p, s -> s.isOf(Blocks.FURNACE) || s.isOf(Blocks.BLAST_FURNACE) || s.isOf(Blocks.SMOKER), 5)) {
            bot.note("FAILED smelt: no furnace within 5 blocks (craft a furnace from 8 cobblestone, place it, stand next to it)");
            return true;
        }
        SmeltingRecipe recipe = null;
        for (SmeltingRecipe r : w.getRecipeManager().listAllOfType(RecipeType.SMELTING)) {
            if (r.getOutput(w.getRegistryManager()).isOf(out)) {
                if (recipe == null) recipe = r;
                // prefer the recipe whose input we actually carry
                for (ItemStack s : p.getInventory().main) if (!s.isEmpty() && r.getIngredients().get(0).test(s)) { recipe = r; break; }
            }
        }
        if (recipe == null) {
            bot.note("FAILED smelt: no smelting recipe produces '" + item + "' (give the OUTPUT id, e.g. iron_ingot, cooked_beef, charcoal)");
            return true;
        }
        Inventory inv = p.getInventory();
        int have = 0;
        for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty() && recipe.getIngredients().get(0).test(inv.getStack(i))) have += inv.getStack(i).getCount();
        int n = Math.min(count, have);
        if (n == 0) {
            bot.note("FAILED smelt " + item + ": you carry no input item for it");
            return true;
        }
        // fuel
        Map<Item, Integer> fuels = AbstractFurnaceBlockEntity.createFuelTimeMap();
        int need = n * recipe.getCookTime();
        int slotFuel = -1;
        for (int i = 0; i < inv.size() && slotFuel < 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && fuels.containsKey(s.getItem()) && !recipe.getIngredients().get(0).test(s)) slotFuel = i;
        }
        if (slotFuel < 0) {
            bot.note("FAILED smelt " + item + ": no fuel (coal, charcoal, planks, logs, sticks...)");
            return true;
        }
        // consume fuel items until the burn time covers the job
        int burn = 0;
        while (burn < need) {
            int slot = -1;
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isEmpty() && fuels.containsKey(s.getItem()) && !recipe.getIngredients().get(0).test(s)) { slot = i; break; }
            }
            if (slot < 0) { // out of fuel - cook fewer
                n = Math.max(1, burn / recipe.getCookTime());
                break;
            }
            burn += fuels.get(inv.getStack(slot).getItem());
            inv.getStack(slot).decrement(1);
        }
        int left = n;
        for (int i = 0; i < inv.size() && left > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && recipe.getIngredients().get(0).test(s)) {
                int take = Math.min(left, s.getCount());
                s.decrement(take);
                left -= take;
            }
        }
        ItemStack o = recipe.getOutput(w.getRegistryManager()).copy();
        o.setCount(o.getCount() * n);
        result = o;
        wait = Math.min(200, 10 * n) + 1;
        return false;
    }
}
