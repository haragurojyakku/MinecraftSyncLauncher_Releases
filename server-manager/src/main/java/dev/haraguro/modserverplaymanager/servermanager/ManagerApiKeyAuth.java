package dev.haraguro.modserverplaymanager.servermanager;

import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;

/**
 * Optional shared-secret gate for every /api/manager/* endpoint except
 * health — same shape as sync-server's ApiKeyAuth. server-manager can start
 * or stop the actual game server and trigger backups, so this is more
 * sensitive to leave open than sync-server's read-mostly endpoints once
 * reachable beyond a LAN.
 */
public final class ManagerApiKeyAuth {

    private ManagerApiKeyAuth() {
    }

    /** No-op if apiKey is null/blank — matches sync-server's open-by-default LAN behavior. */
    public static void register(Javalin app, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        app.before(ctx -> {
            // Deliberately reachable without the admin manager API key — see
            // Routes.MANAGER_BACKUP_PARTICIPANT_REQUEST's own javadoc for why
            // (it's gated by BackupSettings.allowParticipantTriggeredBackup instead).
            if (ctx.path().equals(Routes.MANAGER_HEALTH) || ctx.path().equals(Routes.MANAGER_BACKUP_PARTICIPANT_REQUEST)) {
                return;
            }
            if (!apiKey.equals(ctx.header(Routes.API_KEY_HEADER))) {
                throw new UnauthorizedResponse("Missing or invalid " + Routes.API_KEY_HEADER + " header");
            }
        });
    }
}
