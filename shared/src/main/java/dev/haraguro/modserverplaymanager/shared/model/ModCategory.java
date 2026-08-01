package dev.haraguro.modserverplaymanager.shared.model;

/**
 * Whether a mod in server/mods is needed by the server, the player's client,
 * or both. This is independent of {@link ManagedFile#isEnabled()}: a disabled
 * mod still has a category, it's just not currently active either side.
 *
 * Only BOTH and CLIENT_ONLY mods are ever advertised in the {@link SyncManifest}
 * handed to players — SERVER_ONLY mods stay on the server (so it can load
 * them) but are never downloaded by the launcher, since the player doesn't
 * need them. See ServerFilesService.buildManifest.
 */
public enum ModCategory {
    /** Needed by both the server and the client. The default — matches the pre-existing full-sync behavior. */
    BOTH,
    /** Needed for the server to run, but not by players — excluded from the sync manifest. */
    SERVER_ONLY,
    /** Not needed for the server to run, but players need it — kept in the sync manifest. */
    CLIENT_ONLY
}
