package dev.xardas.nekoroshop;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The shop window holds only the player's inventory; the offers are drawn by the screen and every trade goes
 * through Payloads.Trade, so nothing can be left inside when the window closes.
 */
public final class ShopMenu extends AbstractContainerMenu {
    public static final int INVENTORY_X = 60, INVENTORY_Y = 156, HOTBAR_Y = 214;

    private final ContainerLevelAccess access;

    /** On the client. */
    public ShopMenu(int id, Inventory inventory) {
        this(id, inventory, ContainerLevelAccess.NULL);
    }

    public ShopMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        super(NekoroShop.MENU.get(), id);
        this.access = access;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, HOTBAR_Y));
        }
    }

    /** Shift+click moves between the hotbar and the rest of the inventory. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        boolean moved = index < 27 ? moveItemStackTo(stack, 27, 36, false) : moveItemStackTo(stack, 0, 27, false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, NekoroShop.BLOCK.get());
    }
}
