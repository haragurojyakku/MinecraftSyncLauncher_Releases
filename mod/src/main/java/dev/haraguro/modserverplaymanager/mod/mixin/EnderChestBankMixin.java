package dev.haraguro.modserverplaymanager.mod.mixin;

import dev.haraguro.modserverplaymanager.mod.bank.client.BankPanelScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Bank" button to the player's own ender chest screen (and only that
 * screen — a regular chest reuses the same {@link ChestMenu}/generic screen
 * class in vanilla, so we key off the backing container being specifically a
 * {@link PlayerEnderChestContainer}). Opens {@link BankPanelScreen}.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class EnderChestBankMixin extends Screen {

    @Shadow
    @Final
    protected AbstractContainerMenu menu;

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    protected EnderChestBankMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void mcsync$addBankButton(CallbackInfo ci) {
        if (!(menu instanceof ChestMenu chestMenu) || !(chestMenu.getContainer() instanceof PlayerEnderChestContainer)) {
            return;
        }
        addRenderableWidget(Button.builder(Component.translatable("mcsync.bank.ender_chest_button"),
                        b -> {
                            if (minecraft != null) {
                                minecraft.setScreenAndShow(new BankPanelScreen(this));
                            }
                        })
                .bounds(leftPos + imageWidth + 6, topPos, 80, 20)
                .build());
    }
}
