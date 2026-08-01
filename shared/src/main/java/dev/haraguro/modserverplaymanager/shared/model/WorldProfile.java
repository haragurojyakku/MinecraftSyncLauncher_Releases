package dev.haraguro.modserverplaymanager.shared.model;

/**
 * One world save under server/ (a top-level directory containing a
 * level.dat). "active" means its name matches server.properties' level-name
 * right now — see server-manager's WorldProfileService, which is the only
 * thing that ever constructs these (list() rescans the filesystem each
 * call, same as WhitelistService — no separate registry file to drift).
 */
public class WorldProfile {

    private String name;
    private long sizeBytes;
    private long lastModifiedMillis;
    private boolean active;

    public WorldProfile() {
    }

    public WorldProfile(String name, long sizeBytes, long lastModifiedMillis, boolean active) {
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.lastModifiedMillis = lastModifiedMillis;
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public boolean isActive() {
        return active;
    }
}
