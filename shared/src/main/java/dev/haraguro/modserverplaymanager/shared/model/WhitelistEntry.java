package dev.haraguro.modserverplaymanager.shared.model;

/**
 * One entry of server/whitelist.json, as managed by server-manager's
 * /api/manager/whitelist endpoint. Mirrors vanilla Minecraft's own
 * whitelist.json shape exactly (uuid + name only — vanilla also allows a
 * legacy "flat" array-of-strings whitelist.json, but every server this
 * project manages runs in offline mode, so uuid is always the deterministic
 * offline UUID, never a real Mojang account id).
 */
public class WhitelistEntry {

    private String uuid;
    private String name;

    public WhitelistEntry() {
    }

    public WhitelistEntry(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }
}
