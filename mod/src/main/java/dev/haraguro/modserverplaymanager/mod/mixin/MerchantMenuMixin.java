package dev.haraguro.modserverplaymanager.mod.mixin;

import dev.haraguro.modserverplaymanager.mod.bank.BankTradeService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Before vanilla's quick-trade fills the payment slot from the player's own
 * inventory, top up any emerald shortfall from their bank balance — see
 * BankTradeService. This is what lets a trade succeed without the player
 * physically carrying emeralds.
 */
@Mixin(MerchantMenu.class)
public abstract class MerchantMenuMixin {

    @Shadow
    @Final
    private Merchant trader;

    @Shadow
    public abstract MerchantOffers getOffers();

    @Inject(method = "tryMoveItems", at = @At("HEAD"))
    private void mcsync$topUpEmeraldCost(int index, CallbackInfo ci) {
        if (!(trader.getTradingPlayer() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        MerchantOffers offers = getOffers();
        if (index < 0 || index >= offers.size()) {
            return;
        }
        MerchantOffer offer = offers.get(index);
        BankTradeService.topUpEmeraldShortfall(serverPlayer, offer.getItemCostA());
        offer.getItemCostB().ifPresent(cost -> BankTradeService.topUpEmeraldShortfall(serverPlayer, cost));
    }
}
