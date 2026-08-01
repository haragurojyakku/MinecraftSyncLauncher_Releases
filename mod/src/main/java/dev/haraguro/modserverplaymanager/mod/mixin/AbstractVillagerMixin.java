package dev.haraguro.modserverplaymanager.mod.mixin;

import dev.haraguro.modserverplaymanager.mod.bank.BankTradeService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After a trade completes, if the result is emeralds, sweep them into the
 * player's bank balance instead of leaving them as physical items. Deferred
 * one server tick so the result has actually settled into the player's
 * inventory/cursor before we go looking for it (see BankTradeService).
 */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {

    @Inject(method = "notifyTrade", at = @At("TAIL"))
    private void mcsync$sweepEmeraldResult(MerchantOffer offer, CallbackInfo ci) {
        if (offer.getResult().getItem() != Items.EMERALD) {
            return;
        }
        AbstractVillager self = (AbstractVillager) (Object) this;
        if (!(self.getTradingPlayer() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        int amount = offer.getResult().getCount();
        MinecraftServer server = serverPlayer.level().getServer();
        server.execute(() -> BankTradeService.sweepEmeraldResult(serverPlayer, amount));
    }
}
