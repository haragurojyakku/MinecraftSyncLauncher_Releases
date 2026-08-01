package dev.haraguro.modserverplaymanager.syncserver;

import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;

/**
 * Optional shared-secret gate for every endpoint except /api/health. This
 * project has no real account system (see OfflineAuth), so once sync-server
 * is reachable from the internet — not just a LAN — an API key is the only
 * thing stopping strangers from reading the mod list or player data.
 */
public final class SyncKeyAuth {

    private SyncKeyAuth() {
    }

    /** No-op if apiKey is null/blank — matches today's open-by-default LAN behavior. */
    public static void register(Javalin app, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        app.before(ctx -> {
            if (ctx.path().equals(Routes.HEALTH)) {
                return;
            }
            if (!apiKey.equals(ctx.header(Routes.API_KEY_HEADER))) {
                throw new UnauthorizedResponse("Missing or invalid " + Routes.API_KEY_HEADER + " header");
            }
        });
    }
}
