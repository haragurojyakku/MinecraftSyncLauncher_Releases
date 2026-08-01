package dev.haraguro.modserverplaymanager.mod.mixin;

import com.mojang.authlib.GameProfile;
import dev.haraguro.modserverplaymanager.mod.skin.SkinCache;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Substitutes a player's resolved skin with our own custom one when
 * {@link SkinCache} has one ready — this is the whole point of not depending
 * on CustomSkinLoader or a similar mod: the skin is server-independent (this
 * project's own sync-server), and this Mixin is the only thing that actually
 * makes a downloaded PNG show up in-game.
 *
 * Only the body texture is swapped; cape/elytra/model (arm width) pass
 * through from whatever the vanilla lookup already resolved, since a custom
 * skin here means "we have a body texture for you", not a full profile.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {

    @Shadow
    public abstract GameProfile getProfile();

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void mcsync$overrideSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        Identifier customTexture = SkinCache.getRegisteredTexture(getProfile().id());
        if (customTexture == null) {
            return;
        }
        PlayerSkin original = cir.getReturnValue();
        cir.setReturnValue(PlayerSkin.insecure(
                new ClientAsset.ResourceTexture(customTexture),
                original.cape(),
                original.elytra(),
                original.model()));
    }
}
