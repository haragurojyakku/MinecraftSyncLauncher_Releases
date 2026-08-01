package dev.haraguro.modserverplaymanager.shared.model;

/**
 * sync-server's own admin-facing behavior toggles — as opposed to
 * {@link ServerSettings}, which is curated server.properties. Currently
 * whether the mod/resource pack upload admin endpoints refuse to overwrite an
 * existing file of the same name (enabled or disabled) instead of silently
 * replacing it, and the host-entered public Minecraft address (host:port)
 * that a client can fetch via this same API-key-gated endpoint instead of
 * being told it out of band. Defaults to false/null (the original "uploads
 * always overwrite, no published address" behavior) so upgrading this
 * project doesn't silently change behavior.
 */
public class SyncServerSettings {

    private boolean preventOverwriteOnUpload;
    private String publicGameServerAddress;

    public SyncServerSettings() {
    }

    public SyncServerSettings(boolean preventOverwriteOnUpload) {
        this.preventOverwriteOnUpload = preventOverwriteOnUpload;
    }

    public SyncServerSettings(boolean preventOverwriteOnUpload, String publicGameServerAddress) {
        this.preventOverwriteOnUpload = preventOverwriteOnUpload;
        this.publicGameServerAddress = publicGameServerAddress;
    }

    public boolean isPreventOverwriteOnUpload() {
        return preventOverwriteOnUpload;
    }

    public String getPublicGameServerAddress() {
        return publicGameServerAddress;
    }
}
