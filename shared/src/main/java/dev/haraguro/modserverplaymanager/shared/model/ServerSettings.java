package dev.haraguro.modserverplaymanager.shared.model;

/**
 * The curated subset of server/server.properties the launcher GUI exposes
 * as an editable form (see server-manager's ServerSettingsService for the
 * key mapping). Deliberately not the full properties file — structural/
 * security-sensitive keys (rcon.*, enable-rcon, server-port, online-mode)
 * stay hand-edit-only. Changes only take effect on the next server restart,
 * since Minecraft reads server.properties once at startup.
 */
public class ServerSettings {

    private String difficulty;
    private String gamemode;
    private String motd;
    private int maxPlayers;
    private boolean whitelist;
    private boolean hardcore;
    private int viewDistance;
    private int simulationDistance;
    private int spawnProtection;
    private boolean allowFlight;

    public ServerSettings() {
    }

    public ServerSettings(String difficulty, String gamemode, String motd, int maxPlayers, boolean whitelist,
                           boolean hardcore, int viewDistance, int simulationDistance, int spawnProtection,
                           boolean allowFlight) {
        this.difficulty = difficulty;
        this.gamemode = gamemode;
        this.motd = motd;
        this.maxPlayers = maxPlayers;
        this.whitelist = whitelist;
        this.hardcore = hardcore;
        this.viewDistance = viewDistance;
        this.simulationDistance = simulationDistance;
        this.spawnProtection = spawnProtection;
        this.allowFlight = allowFlight;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getGamemode() {
        return gamemode;
    }

    public String getMotd() {
        return motd;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public boolean isWhitelist() {
        return whitelist;
    }

    public boolean isHardcore() {
        return hardcore;
    }

    public int getViewDistance() {
        return viewDistance;
    }

    public int getSimulationDistance() {
        return simulationDistance;
    }

    public int getSpawnProtection() {
        return spawnProtection;
    }

    public boolean isAllowFlight() {
        return allowFlight;
    }
}
