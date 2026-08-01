package dev.haraguro.modserverplaymanager.mod.skin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.haraguro.modserverplaymanager.mod.SyncMod;
import dev.haraguro.modserverplaymanager.mod.api.ApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of "which players have a custom skin registered as a
 * texture right now" — the synchronous half of the skin override (see
 * PlayerInfoMixin, which reads {@link #getRegisteredTexture} on every call
 * to PlayerInfo.getSkin() and can never block on network I/O there).
 *
 * {@link #refreshAsync} is the asynchronous half: fetch the current sha1
 * from sync-server, skip if unchanged, otherwise download the PNG and
 * register it as a texture. Call it periodically (see SyncModClient) for
 * every online player's UUID — cheap no-op when nothing changed since the
 * hash check alone is a tiny GET.
 */
public final class SkinCache {

    private static final Map<UUID, Identifier> READY = new ConcurrentHashMap<>();
    private static final Map<UUID, String> KNOWN_HASH = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private SkinCache() {
    }

    /** @return the registered texture Identifier for this player's custom skin, or null if none/not loaded yet. */
    public static Identifier getRegisteredTexture(UUID uuid) {
        return READY.get(uuid);
    }

    public static void refreshAsync(UUID uuid, ApiClient apiClient) {
        if (!IN_FLIGHT.add(uuid)) {
            return; // already checking this player
        }
        apiClient.fetchSkinHashAsync(uuid)
                .thenCompose(hashOpt -> {
                    if (hashOpt.isEmpty() || hashOpt.get().equals(KNOWN_HASH.get(uuid))) {
                        return CompletableFuture.completedFuture(null);
                    }
                    String hash = hashOpt.get();
                    return apiClient.fetchSkinBytesAsync(uuid)
                            .thenAccept(bytesOpt -> bytesOpt.ifPresent(bytes -> registerTexture(uuid, hash, bytes)));
                })
                .whenComplete((v, error) -> {
                    if (error != null) {
                        SyncMod.LOGGER.debug("Skin refresh failed for {}: {}", uuid, error.toString());
                    }
                    IN_FLIGHT.remove(uuid);
                });
    }

    /** Texture upload must happen on the render thread — see Minecraft.execute(). */
    private static void registerTexture(UUID uuid, String hash, byte[] pngBytes) {
        Minecraft.getInstance().execute(() -> {
            try {
                NativeImage image = NativeImage.read(pngBytes);
                Identifier id = Identifier.fromNamespaceAndPath(SyncMod.MOD_ID, "skins/" + uuid);
                DynamicTexture texture = new DynamicTexture(() -> "mcsync custom skin " + uuid, image);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                READY.put(uuid, id);
                KNOWN_HASH.put(uuid, hash);
            } catch (IOException e) {
                SyncMod.LOGGER.warn("Failed to decode custom skin for {}: {}", uuid, e.toString());
            }
        });
    }
}
