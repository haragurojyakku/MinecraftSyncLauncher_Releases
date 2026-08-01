package dev.haraguro.modserverplaymanager.shared.protocol;

/**
 * REST endpoint paths exposed by sync-server and consumed by launcher/mod.
 * Keeping these in one place stops the three modules drifting apart.
 */
public final class Routes {

    public static final String HEALTH = "/api/health";

    /** Sent by launcher, checked by sync-server's optional SyncKeyAuth — see server/README.md. */
    public static final String API_KEY_HEADER = "X-Api-Key";

    // Mod/resource pack sync (launcher <- sync-server, backed by server/mods and server/resourcepacks)
    public static final String SYNC_MANIFEST = "/api/sync/manifest";
    public static final String SYNC_MODS_DOWNLOAD = "/api/sync/mods/{file}";
    public static final String SYNC_RESOURCEPACKS_DOWNLOAD = "/api/sync/resourcepacks/{file}";

    // Mod/resource pack admin (launcher -> sync-server): add/remove/enable-disable
    // server-side files. GET lists (including disabled ones), POST uploads;
    // SYNC_*_DOWNLOAD above doubles as the DELETE path for the same file.
    public static final String SYNC_MODS_LIST = "/api/sync/mods";
    public static final String SYNC_MODS_TOGGLE = "/api/sync/mods/{file}/toggle";
    // Server-only/client-only/both classification — see ModCategory. Mods only;
    // resource packs are never installed server-side so the distinction doesn't apply to them.
    public static final String SYNC_MODS_CATEGORY = "/api/sync/mods/{file}/category";
    public static final String SYNC_RESOURCEPACKS_LIST = "/api/sync/resourcepacks";
    public static final String SYNC_RESOURCEPACKS_TOGGLE = "/api/sync/resourcepacks/{file}/toggle";

    // sync-server's own admin behavior toggles (as opposed to MANAGER_SETTINGS, which is
    // curated server.properties) — see SyncServerSettings.
    public static final String SYNC_SETTINGS = "/api/sync/settings";

    // Player data (mod <-> sync-server)
    public static final String PLAYER_BY_UUID = "/api/players/{uuid}";

    // Player balance (mod -> sync-server, server-side only — see BankCommands/bank
    // networking in :mod). Emeralds are the in-game representation of this balance,
    // fixed at a 1:1 rate; see BalanceChangeRequest/BalanceTransferRequest.
    public static final String PLAYER_BALANCE_DEPOSIT = "/api/players/{uuid}/balance/deposit";
    public static final String PLAYER_BALANCE_WITHDRAW = "/api/players/{uuid}/balance/withdraw";
    public static final String PLAYER_BALANCE_TRANSFER = "/api/players/{uuid}/balance/transfer";

    // Player skins (launcher uploads, mod downloads) — see SkinStorageService/SkinRoutes.
    // No shared model for the hash response: it's a single "sha1" field consumed only by
    // the mod's ApiClient, which parses it as a raw JsonObject like fetchPlayerProfileAsync does.
    public static final String PLAYER_SKIN = "/api/players/{uuid}/skin";
    public static final String PLAYER_SKIN_HASH = "/api/players/{uuid}/skin/hash";

    // External services, e.g. exchange rates for in-game economy mods
    public static final String EXCHANGE_RATES = "/api/external/exchange-rates";

    // Server process supervision + backups (server-manager, separate from sync-server)
    public static final String MANAGER_HEALTH = "/api/manager/health";
    public static final String MANAGER_STATUS = "/api/manager/status";
    public static final String MANAGER_BACKUP = "/api/manager/backup";
    public static final String MANAGER_BACKUPS = "/api/manager/backups";
    public static final String MANAGER_STOP = "/api/manager/stop";
    public static final String MANAGER_RESTART = "/api/manager/restart";
    public static final String MANAGER_SETTINGS = "/api/manager/settings";
    public static final String MANAGER_RESTART_SCHEDULE = "/api/manager/restart-schedule";
    public static final String MANAGER_RESTART_SCHEDULE_CANCEL = "/api/manager/restart-schedule/cancel";
    public static final String MANAGER_WHITELIST = "/api/manager/whitelist";
    public static final String MANAGER_WHITELIST_ENTRY = "/api/manager/whitelist/{name}";
    public static final String MANAGER_CONNECTION_ATTEMPTS = "/api/manager/connection-attempts";
    // Backup policy (retention/triggers/cooldown/participant permission) — see BackupSettings.
    public static final String MANAGER_BACKUP_SETTINGS = "/api/manager/backup/settings";

    // World save profiles — server/<name>/ directories switched via server.properties'
    // level-name, not copied on every switch. See WorldProfileService.
    public static final String MANAGER_WORLDS = "/api/manager/worlds";
    public static final String MANAGER_WORLDS_ENTRY = "/api/manager/worlds/{name}";
    public static final String MANAGER_WORLDS_ACTIVATE = "/api/manager/worlds/{name}/activate";
    public static final String MANAGER_WORLDS_RENAME = "/api/manager/worlds/{name}/rename";

    // UPnP-based automatic router port mapping status — read-only, see
    // server-manager's PortForwardingService.
    public static final String MANAGER_PORT_FORWARDING_STATUS = "/api/manager/portforwarding/status";
    // Gated by BackupSettings.allowParticipantTriggeredBackup + the same login/logout cooldown —
    // meant for a future in-game command to call, not exposed in this project's own UI yet.
    public static final String MANAGER_BACKUP_PARTICIPANT_REQUEST = "/api/manager/backup/participant-request";

    private Routes() {
    }
}
