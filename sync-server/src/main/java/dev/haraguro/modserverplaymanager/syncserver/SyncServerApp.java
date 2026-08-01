package dev.haraguro.modserverplaymanager.syncserver;

import dev.haraguro.modserverplaymanager.syncserver.routes.ExternalRoutes;
import dev.haraguro.modserverplaymanager.syncserver.routes.PlayerRoutes;
import dev.haraguro.modserverplaymanager.syncserver.routes.SkinRoutes;
import dev.haraguro.modserverplaymanager.syncserver.routes.SyncRoutes;
import dev.haraguro.modserverplaymanager.syncserver.sync.ServerFilesService;
import dev.haraguro.modserverplaymanager.syncserver.sync.SkinStorageService;
import dev.haraguro.modserverplaymanager.syncserver.sync.SyncServerSettingsService;
import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import io.javalin.Javalin;

import java.nio.file.Path;

public class SyncServerApp {

    public static void main(String[] args) {
        int port = Integer.getInteger("mcsync.port", 7070);
        // Defaults to the sibling server/ runtime environment (see server/README.md).
        Path serverDir = Path.of(System.getProperty("mcsync.server.dir", "../server"));

        SyncServerSettingsService settingsService = new SyncServerSettingsService(serverDir);
        ServerFilesService filesService = new ServerFilesService(serverDir, settingsService);
        PlayerRoutes playerRoutes = new PlayerRoutes(serverDir);
        SyncRoutes syncRoutes = new SyncRoutes(filesService);
        SkinRoutes skinRoutes = new SkinRoutes(new SkinStorageService(serverDir));
        ExternalRoutes externalRoutes = new ExternalRoutes();

        Javalin app = Javalin.create(config -> config.jsonMapper(new GsonJsonMapper()));

        String apiKey = System.getProperty("mcsync.api.key");
        SyncKeyAuth.register(app, apiKey);

        app.get(Routes.HEALTH, ctx -> ctx.result("ok"));

        syncRoutes.register(app);
        playerRoutes.register(app);
        skinRoutes.register(app);
        externalRoutes.register(app);

        app.start(port);
        System.out.println("mcsync sync-server listening on :" + port + " (server dir: " + serverDir.toAbsolutePath() + ")");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("WARNING: no API key set (-Dmcsync.api.key=...). Anyone who can reach this port can "
                    + "read the mod list and player data, AND add/remove/enable/disable mods and resource packs. "
                    + "Fine for LAN-only use; set a key before exposing this to the internet.");
        } else {
            System.out.println("API key required on all endpoints except " + Routes.HEALTH + ".");
        }
    }
}
