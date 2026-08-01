package dev.haraguro.modserverplaymanager.launcher.launch;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Same offline-UUID derivation vanilla Minecraft uses for cracked/LAN play. */
public final class OfflineAuth {

    private OfflineAuth() {
    }

    public static AuthSession session(String playerName) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
        return new AuthSession(playerName, offlineUuid, "-", "legacy", "0");
    }
}
