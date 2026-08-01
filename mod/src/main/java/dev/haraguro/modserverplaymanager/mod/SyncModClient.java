package dev.haraguro.modserverplaymanager.mod;

import dev.haraguro.modserverplaymanager.mod.bank.client.BankPanelScreen;
import dev.haraguro.modserverplaymanager.mod.bank.network.BankBalancePayload;
import dev.haraguro.modserverplaymanager.mod.skin.SkinCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Client-only entrypoint (see fabric.mod.json's "client" list) — periodically
 * checks every online player's custom-skin hash and refreshes the cached
 * texture on change (see SkinCache). PlayerInfoMixin does the actual
 * substitution; this just keeps SkinCache warm.
 */
public class SyncModClient implements ClientModInitializer {

    // Every 5s (20 ticks/s) is plenty for a hash check to feel responsive
    // without hammering sync-server once per player per tick.
    private static final int REFRESH_INTERVAL_TICKS = 100;

    private int ticksUntilRefresh = 0;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(BankBalancePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof BankPanelScreen bankPanel) {
                bankPanel.onBalanceUpdate(payload.balance());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (--ticksUntilRefresh > 0) {
                return;
            }
            ticksUntilRefresh = REFRESH_INTERVAL_TICKS;

            ClientPacketListener connection = client.getConnection();
            if (connection == null || SyncMod.apiClient() == null) {
                return;
            }
            connection.getOnlinePlayers().forEach(playerInfo ->
                    SkinCache.refreshAsync(playerInfo.getProfile().id(), SyncMod.apiClient()));
        });
    }
}
