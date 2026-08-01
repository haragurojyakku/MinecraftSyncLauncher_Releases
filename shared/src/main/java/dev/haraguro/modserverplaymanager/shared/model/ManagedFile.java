package dev.haraguro.modserverplaymanager.shared.model;

/**
 * One mod or resource pack file as seen by the launcher's admin (add/remove/
 * enable-disable) view of server/mods or server/resourcepacks — as opposed
 * to {@link SyncFile}, which only ever describes enabled files headed for
 * the player-facing sync manifest. fileName is always the base name (e.g.
 * "foo.jar"), never the on-disk "foo.jar.disabled" form — enabled tells you
 * which of the two actually exists right now.
 */
public class ManagedFile {

    private String fileName;
    private long sizeBytes;
    private boolean enabled;
    private ModCategory category;

    public ManagedFile() {
    }

    public ManagedFile(String fileName, long sizeBytes, boolean enabled, ModCategory category) {
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.enabled = enabled;
        this.category = category;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ModCategory getCategory() {
        return category;
    }
}
