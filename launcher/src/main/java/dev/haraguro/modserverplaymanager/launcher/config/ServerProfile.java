package dev.haraguro.modserverplaymanager.launcher.config;

/**
 * One selectable server: where sync-server lives, where the Minecraft
 * server itself listens, and which game version it expects. Friends
 * running multiple servers (or a test + production pair) add entries here
 * by hand-editing launcher-config.json; the GUI only selects among them.
 *
 * @param apiKey sent as the X-Api-Key header to sync-server if non-blank —
 *               required once sync-server is reachable from the internet
 *               and SyncKeyAuth is configured server-side, see server/README.md.
 * @param managerBaseUrl where server-manager's HTTP API lives (separate
 *                       process/port from sync-server) — used by the Server
 *                       Manager tab's status/start/stop/backup controls.
 * @param managerApiKey  sent as the X-Api-Key header to server-manager if
 *                       non-blank, mirroring apiKey above but for the
 *                       manager's own -Dmcsync.manager.api.key.
 */
public record ServerProfile(
        String name,
        String apiBaseUrl,
        String serverAddress,
        String minecraftVersion,
        String fabricLoaderVersion,
        String apiKey,
        String managerBaseUrl,
        String managerApiKey
) {
    public static final ServerProfile DEFAULT = new ServerProfile(
            "Default", "http://localhost:7070", "localhost:25565", "26.2", "0.19.3", "",
            "http://localhost:7080", "");
}
