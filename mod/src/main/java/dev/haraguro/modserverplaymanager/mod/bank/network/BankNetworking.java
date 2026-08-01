package dev.haraguro.modserverplaymanager.mod.bank.network;

import dev.haraguro.modserverplaymanager.mod.SyncMod;
import dev.haraguro.modserverplaymanager.mod.api.ApiClient;
import dev.haraguro.modserverplaymanager.mod.bank.EmeraldUtil;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Registers the bank payload types (both sides must agree on these — see
 * SyncModClient for the client-side registration/receiver) and the
 * server-side handlers for the ender chest bank panel's deposit/withdraw/
 * balance requests. All inventory/network side effects run on the server
 * thread; only the sync-server HTTP call happens off-thread.
 */
public final class BankNetworking {

    private BankNetworking() {
    }

    /** Called from SyncMod.onInitialize() — runs on both dedicated server and integrated (client) server. */
    public static void registerTypesAndServerReceivers() {
        PayloadTypeRegistry.serverboundPlay().register(BankBalanceRequestPayload.TYPE, BankBalanceRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BankDepositRequestPayload.TYPE, BankDepositRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BankWithdrawRequestPayload.TYPE, BankWithdrawRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BankBalancePayload.TYPE, BankBalancePayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(BankBalanceRequestPayload.TYPE,
                (payload, context) -> sendBalance(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(BankDepositRequestPayload.TYPE,
                (payload, context) -> handleDeposit(context.player(), payload.amount()));
        ServerPlayNetworking.registerGlobalReceiver(BankWithdrawRequestPayload.TYPE,
                (payload, context) -> handleWithdraw(context.player(), payload.amount()));
    }

    private static void sendBalance(ServerPlayer player) {
        ApiClient apiClient = SyncMod.apiClient();
        if (apiClient == null) {
            return;
        }
        apiClient.fetchPlayerProfileAsync(player.getUUID()).whenComplete((profile, error) -> {
            if (error != null) {
                SyncMod.LOGGER.warn("Failed to fetch balance for {}: {}", player.getGameProfile().name(), error.toString());
                return;
            }
            player.level().getServer().execute(() ->
                    ServerPlayNetworking.send(player, new BankBalancePayload(profile.get("balance").getAsDouble())));
        });
    }

    private static void handleDeposit(ServerPlayer player, int amount) {
        if (amount <= 0) {
            return;
        }
        int toDeposit = Math.min(amount, player.getInventory().countItem(Items.EMERALD));
        if (toDeposit <= 0) {
            return;
        }
        ApiClient apiClient = SyncMod.apiClient();
        if (apiClient == null) {
            return;
        }
        apiClient.depositAsync(player.getUUID(), toDeposit).whenComplete((profile, error) -> {
            if (error != null) {
                SyncMod.LOGGER.warn("Deposit failed for {}: {}", player.getGameProfile().name(), error.toString());
                return;
            }
            player.level().getServer().execute(() -> {
                EmeraldUtil.remove(player.getInventory(), toDeposit);
                ServerPlayNetworking.send(player, new BankBalancePayload(profile.get("balance").getAsDouble()));
            });
        });
    }

    private static void handleWithdraw(ServerPlayer player, int amount) {
        if (amount <= 0) {
            return;
        }
        ApiClient apiClient = SyncMod.apiClient();
        if (apiClient == null) {
            return;
        }
        apiClient.withdrawAsync(player.getUUID(), amount).whenComplete((profile, error) -> {
            if (error != null) {
                if (!(error.getCause() instanceof ApiClient.InsufficientBalanceException)) {
                    SyncMod.LOGGER.warn("Withdraw failed for {}: {}", player.getGameProfile().name(), error.toString());
                }
                return;
            }
            player.level().getServer().execute(() -> {
                player.getInventory().add(new ItemStack(Items.EMERALD, amount));
                ServerPlayNetworking.send(player, new BankBalancePayload(profile.get("balance").getAsDouble()));
            });
        });
    }
}
