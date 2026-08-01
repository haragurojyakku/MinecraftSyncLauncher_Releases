package dev.haraguro.modserverplaymanager.mod.bank;

import dev.haraguro.modserverplaymanager.mod.SyncMod;
import dev.haraguro.modserverplaymanager.mod.api.ApiClient;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;

import java.util.concurrent.CompletionException;

/**
 * Redirects the emerald leg of villager trades to the player's bank balance
 * instead of physical items:
 * <ul>
 *   <li>Buy side (paying emeralds): {@link MerchantMenuMixin} calls
 *       {@link #topUpEmeraldShortfall} before vanilla fills the payment slot
 *       from the player's inventory, so a trade succeeds even with zero
 *       physical emeralds on hand.</li>
 *   <li>Sell side (receiving emeralds): {@link AbstractVillagerMixin} calls
 *       {@link #sweepEmeraldResult} a tick after the trade completes, once
 *       the result has settled into the player's inventory/cursor.</li>
 * </ul>
 * Both directions are 1:1 (1 emerald = 1 currency unit).
 */
public final class BankTradeService {

    private BankTradeService() {
    }

    public static void topUpEmeraldShortfall(ServerPlayer player, ItemCost cost) {
        if (cost.itemStack().getItem() != Items.EMERALD) {
            return;
        }
        int needed = cost.count();
        int have = player.getInventory().countItem(Items.EMERALD);
        int shortfall = needed - have;
        if (shortfall <= 0) {
            return;
        }
        ApiClient apiClient = SyncMod.apiClient();
        if (apiClient == null) {
            return;
        }
        try {
            apiClient.withdrawAsync(player.getUUID(), shortfall).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ApiClient.InsufficientBalanceException) {
                player.sendSystemMessage(Component.translatable("mcsync.bank.insufficient_balance"));
            } else {
                SyncMod.LOGGER.warn("Failed to withdraw {} emeralds for trade top-up ({}): {}",
                        shortfall, player.getGameProfile().name(), e.toString());
            }
            return;
        }
        player.getInventory().add(new ItemStack(Items.EMERALD, shortfall));
    }

    public static void sweepEmeraldResult(ServerPlayer player, int amount) {
        if (amount <= 0) {
            return;
        }
        ApiClient apiClient = SyncMod.apiClient();
        if (apiClient == null) {
            return;
        }
        int removed = removeEmeralds(player, amount);
        if (removed <= 0) {
            return;
        }
        apiClient.depositAsync(player.getUUID(), removed).whenComplete((profile, error) -> {
            if (error != null) {
                SyncMod.LOGGER.warn("Failed to deposit {} emeralds from trade ({}): {}",
                        removed, player.getGameProfile().name(), error.toString());
            }
        });
    }

    private static int removeEmeralds(ServerPlayer player, int amount) {
        int remaining = amount;
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.getItem() == Items.EMERALD) {
            int take = Math.min(remaining, carried.getCount());
            carried.shrink(take);
            player.containerMenu.setCarried(carried);
            remaining -= take;
        }
        remaining -= EmeraldUtil.remove(player.getInventory(), remaining);
        return amount - remaining;
    }
}
