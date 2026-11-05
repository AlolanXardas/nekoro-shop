package dev.xardas.nekoroshop;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Counting, taking and giving items in the player's main inventory (hotbar and the 27 slots above it, what the shop
 * window shows). Used on both sides, so the client can show the same limits the server checks.
 */
public final class Inventories {
    private Inventories() { }

    public static boolean same(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /** How many pieces like template fit. */
    public static int space(Player player, ItemStack template) {
        Inventory inventory = player.getInventory();
        int max = inventory.getMaxStackSize(template);
        long free = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.isEmpty()) free += max;
            else if (same(stack, template)) free += Math.max(0, max - stack.getCount());
        }
        return (int) Math.min(Integer.MAX_VALUE, free);
    }

    public static int count(Player player, ItemStack template) {
        long count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (same(stack, template)) count += stack.getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    /** Takes up to amount pieces, from the last slot first (the hotbar last); returns how many were taken. */
    public static int remove(Player player, ItemStack template, int amount) {
        Inventory inventory = player.getInventory();
        int left = amount;
        for (int i = inventory.items.size() - 1; i >= 0 && left > 0; i--) {
            ItemStack stack = inventory.items.get(i);
            if (!same(stack, template)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        inventory.setChanged();
        return amount - left;
    }

    /** Gives amount pieces; what does not fit (checked before, so normally nothing) drops at the player's feet. */
    public static void give(Player player, ItemStack template, int amount) {
        Inventory inventory = player.getInventory();
        int max = inventory.getMaxStackSize(template);
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, max);
            ItemStack stack = template.copyWithCount(n);
            inventory.add(stack);
            if (!stack.isEmpty()) player.drop(stack, false);
            left -= n;
        }
        inventory.setChanged();
    }
}
