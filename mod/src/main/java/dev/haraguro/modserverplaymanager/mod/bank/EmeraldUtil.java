package dev.haraguro.modserverplaymanager.mod.bank;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Small helper shared by the trade Mixins and the ender chest bank panel networking. */
public final class EmeraldUtil {

    private EmeraldUtil() {
    }

    /** Removes up to {@code amount} physical emerald items from the player's inventory. @return how many were actually removed. */
    public static int remove(Inventory inventory, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() != Items.EMERALD) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            inventory.removeItem(slot, take);
            remaining -= take;
        }
        return amount - remaining;
    }
}
