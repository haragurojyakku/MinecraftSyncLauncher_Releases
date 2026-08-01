package dev.haraguro.modserverplaymanager.launcher.launch;

import java.util.UUID;

/**
 * Whatever identity the game is launched as. Either {@link OfflineAuth}
 * (works only against servers with online-mode=false) or
 * {@link dev.haraguro.modserverplaymanager.launcher.auth.MinecraftAuthenticator}
 * (real Microsoft account, required for online-mode=true servers).
 */
public record AuthSession(String playerName, UUID uuid, String accessToken, String userType, String xuid) {
}
