package dev.haraguro.modserverplaymanager.launcher.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Local launcher settings, stored at ~/.modserverplaymanager/launcher-config.json.
 * A default file is written on first run; migrated in place from older
 * schema versions (see backfillDefaults).
 */
public class LauncherConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".modserverplaymanager");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("launcher-config.json");

    // Null/blank = auto-detect from the OS locale (see Lang.detectFromOs()).
    private String language;

    // Doubles as the game directory: ModSyncService downloads mods/
    // resourcepacks here, matching Minecraft's own installDir/mods,
    // installDir/resourcepacks layout expectations. User-editable via the
    // GUI's Browse... button (DirectoryChooser).
    private String installDir = Path.of(System.getProperty("user.home"), "mcsync", "install").toString();
    // Legacy — player name now lives on the selected LaunchProfile itself (see
    // getPlayerName()/setPlayerName()), tying launch environment and player name together as one
    // profile. Kept only so an older launcher-config.json's top-level playerName can be migrated
    // into the (then-nameless) default launch profile once, in backfillDefaults; never read after that.
    private String playerName = "";

    // Microsoft sign-in (see the `auth` package's MinecraftAuthenticator).
    // msaRefreshToken non-blank is the "signed in with Microsoft" flag itself
    // — no separate boolean, mirroring how `activeServer` alone decides the
    // active connection. All plaintext, same as apiKey/managerApiKey below;
    // this one is a personal credential though, not a shared secret, so
    // Sign Out (clearMsaAccount) is meant to be quick and discoverable.
    private String msaClientId = "";
    private String msaRefreshToken = "";
    private String msaCachedUsername = "";
    private String msaCachedUuid = "";

    // The server actually in use right now — freely editable in the GUI,
    // does NOT need to match anything in `servers` below. Picking a saved
    // preset copies its fields in here as a starting point; typing new
    // values and pressing Connect uses them directly without requiring a
    // save first.
    private ServerProfile activeServer = ServerProfile.DEFAULT;

    // Named presets the "saved servers" picker offers, purely for
    // convenience — add/update one via saveServerPreset(...).
    private List<ServerProfile> servers = new ArrayList<>(List.of(ServerProfile.DEFAULT));

    // Profile names imported from an *encrypted* connection settings file (see
    // ConnectionFileCrypto) — the whole point of encrypting the export is that the recipient can
    // connect without being able to read the sync server URL/game address/API key back out, so the
    // Server tab keeps those three fields hidden (masked, non-editable) for any profile named here
    // even though the real values are still used internally to actually connect. A plain (non-
    // encrypted) import never adds to this set.
    private Set<String> hiddenDetailProfiles = new HashSet<>();

    private List<LaunchProfile> launchProfiles = new ArrayList<>(List.of(LaunchProfile.DEFAULT));
    private String selectedLaunchProfile = LaunchProfile.DEFAULT.name();

    // Whether this launcher should auto-launch server-manager as a local
    // background process (see LocalServiceLauncher) — a primitive, so an
    // older config.json missing this key already deserializes to false via
    // Gson, no backfill needed.
    private boolean hostModeEnabled = false;
    // Absolute path to the local server/ folder, passed to the auto-launched
    // server-manager as -Dmcsync.server.dir. Blank = don't pass the flag,
    // let server-manager fall back to its own ../server default.
    private String serverDirectory = "";

    // Whether the auto-launched server-manager should try to open the
    // Minecraft/sync-server ports on the router automatically via UPnP —
    // same "primitive, no backfill needed" reasoning as hostModeEnabled.
    // Opt-in and OFF by default: unlike hostModeEnabled, this reaches out to
    // the router and changes its NAT configuration, not just local processes.
    private boolean portForwardingEnabled = false;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String languageCode) {
        this.language = languageCode;
        trySave();
    }

    public Path getInstallDir() {
        return Path.of(installDir);
    }

    public void setInstallDir(Path path) {
        this.installDir = path.toString();
        trySave();
    }

    /** The selected launch profile's own player name — see LaunchProfile's javadoc. */
    public String getPlayerName() {
        String name = getSelectedLaunchProfile().playerName();
        return name == null ? "" : name;
    }

    /** Replaces the selected launch profile with a copy carrying the new player name. */
    public void setPlayerName(String playerName) {
        LaunchProfile current = getSelectedLaunchProfile();
        LaunchProfile updated = new LaunchProfile(current.name(), playerName, current.minMemoryMb(), current.maxMemoryMb());
        launchProfiles.removeIf(p -> p.name().equals(current.name()));
        launchProfiles.add(updated);
        trySave();
    }

    public String getMsaClientId() {
        return msaClientId;
    }

    public void setMsaClientId(String clientId) {
        this.msaClientId = clientId;
        trySave();
    }

    public String getMsaRefreshToken() {
        return msaRefreshToken;
    }

    public String getMsaCachedUsername() {
        return msaCachedUsername;
    }

    public String getMsaCachedUuid() {
        return msaCachedUuid;
    }

    public boolean isMsaSignedIn() {
        return msaRefreshToken != null && !msaRefreshToken.isBlank();
    }

    /** Called after a successful interactive sign-in — persists the account + the refresh token for next launch. */
    public void cacheMsaAccount(String username, String uuid, String refreshToken) {
        this.msaCachedUsername = username;
        this.msaCachedUuid = uuid;
        this.msaRefreshToken = refreshToken;
        trySave();
    }

    /** Microsoft may issue a new refresh token on every use — called after a silent refresh, leaves the cached profile untouched. */
    public void updateMsaRefreshToken(String refreshToken) {
        this.msaRefreshToken = refreshToken;
        trySave();
    }

    /** Sign out — reverts to the offline playerName the next time Play is pressed. */
    public void clearMsaAccount() {
        this.msaCachedUsername = "";
        this.msaCachedUuid = "";
        this.msaRefreshToken = "";
        trySave();
    }

    public List<ServerProfile> getServers() {
        return servers;
    }

    public List<LaunchProfile> getLaunchProfiles() {
        return launchProfiles;
    }

    public ServerProfile getActiveServer() {
        return activeServer;
    }

    /** Applies a server (from a preset or freely typed) as the one to sync/connect to. */
    public void setActiveServer(ServerProfile server) {
        this.activeServer = server;
        trySave();
    }

    /** Adds `server` to the saved-presets list, or replaces the existing preset with the same name. */
    public void saveServerPreset(ServerProfile server) {
        servers.removeIf(existing -> existing.name().equals(server.name()));
        servers.add(server);
        trySave();
    }

    /**
     * Removes a saved preset by name. Falls back to a fresh {@link ServerProfile#DEFAULT} if that
     * was the last remaining preset (the servers list/picker must never go empty), and re-points
     * {@code activeServer} at whatever's left if the deleted preset was the one in use.
     */
    public void deleteServerPreset(String name) {
        servers.removeIf(existing -> existing.name().equals(name));
        if (servers.isEmpty()) {
            servers.add(ServerProfile.DEFAULT);
        }
        if (activeServer.name().equals(name)) {
            activeServer = servers.get(0);
        }
        hiddenDetailProfiles.remove(name);
        trySave();
    }

    /**
     * Renames a saved preset — records are immutable, so this is really "replace the entry named
     * {@code oldName} with {@code renamed}" (whose own name may differ, and whose other fields may
     * also have changed in the same edit). If {@code oldName} was the active server, the active
     * server follows the rename too. A hidden-details flag (see hiddenDetailProfiles) follows the
     * rename rather than being lost.
     */
    public void renameServerPreset(String oldName, ServerProfile renamed) {
        servers.removeIf(existing -> existing.name().equals(oldName));
        servers.removeIf(existing -> existing.name().equals(renamed.name()));
        servers.add(renamed);
        if (activeServer.name().equals(oldName)) {
            activeServer = renamed;
        }
        if (hiddenDetailProfiles.remove(oldName)) {
            hiddenDetailProfiles.add(renamed.name());
        }
        trySave();
    }

    public Set<String> getHiddenDetailProfiles() {
        return hiddenDetailProfiles;
    }

    /** Marks {@code name}'s connection details (sync URL/game address/API key) as hidden in the Server tab's UI. */
    public void markDetailsHidden(String name) {
        hiddenDetailProfiles.add(name);
        trySave();
    }

    public LaunchProfile getSelectedLaunchProfile() {
        return findByName(launchProfiles, selectedLaunchProfile, LaunchProfile::name).orElse(launchProfiles.get(0));
    }

    public void selectLaunchProfile(String name) {
        this.selectedLaunchProfile = name;
        trySave();
    }

    /** Adds {@code profile} to the saved launch profiles, or replaces the existing one with the same name. */
    public void saveLaunchProfile(LaunchProfile profile) {
        launchProfiles.removeIf(existing -> existing.name().equals(profile.name()));
        launchProfiles.add(profile);
        trySave();
    }

    /** Same "must never leave the picker empty" / active-follows-the-change shape as deleteServerPreset. */
    public void deleteLaunchProfile(String name) {
        launchProfiles.removeIf(existing -> existing.name().equals(name));
        if (launchProfiles.isEmpty()) {
            launchProfiles.add(LaunchProfile.DEFAULT);
        }
        if (selectedLaunchProfile.equals(name)) {
            selectedLaunchProfile = launchProfiles.get(0).name();
        }
        trySave();
    }

    /** Same "replace the entry named oldName" shape as renameServerPreset. */
    public void renameLaunchProfile(String oldName, LaunchProfile renamed) {
        launchProfiles.removeIf(existing -> existing.name().equals(oldName));
        launchProfiles.removeIf(existing -> existing.name().equals(renamed.name()));
        launchProfiles.add(renamed);
        if (selectedLaunchProfile.equals(oldName)) {
            selectedLaunchProfile = renamed.name();
        }
        trySave();
    }

    public boolean isHostModeEnabled() {
        return hostModeEnabled;
    }

    public void setHostModeEnabled(boolean enabled) {
        this.hostModeEnabled = enabled;
        trySave();
    }

    public boolean isPortForwardingEnabled() {
        return portForwardingEnabled;
    }

    public void setPortForwardingEnabled(boolean enabled) {
        this.portForwardingEnabled = enabled;
        trySave();
    }

    public String getServerDirectory() {
        return serverDirectory;
    }

    public void setServerDirectory(String path) {
        this.serverDirectory = path;
        trySave();
    }

    /**
     * Resets the install directory, active server, and launch profile back
     * to their hard-coded defaults (does not touch saved presets or the
     * language choice). Callers pairing this with a local-file cleanup get
     * a clean slate for testing the update flow from scratch.
     */
    public void resetToDefaults() {
        this.installDir = Path.of(System.getProperty("user.home"), "mcsync", "install").toString();
        this.activeServer = ServerProfile.DEFAULT;
        this.selectedLaunchProfile = LaunchProfile.DEFAULT.name();
        trySave();
    }

    // Convenience delegates so ModSyncService/GameLauncher/ProfileResolver/
    // LaunchCommandBuilder can keep taking a single LauncherConfig without
    // knowing about server/launch-profile selection at all.

    public String getApiBaseUrl() {
        return activeServer.apiBaseUrl();
    }

    public String getApiKey() {
        return activeServer.apiKey();
    }

    public String getServerAddress() {
        return activeServer.serverAddress();
    }

    public String getMinecraftVersion() {
        return activeServer.minecraftVersion();
    }

    public String getFabricLoaderVersion() {
        return activeServer.fabricLoaderVersion();
    }

    public boolean isAutoConnectEnabled() {
        String address = getServerAddress();
        return address != null && !address.isBlank();
    }

    public int getMinMemoryMb() {
        return getSelectedLaunchProfile().minMemoryMb();
    }

    public int getMaxMemoryMb() {
        return getSelectedLaunchProfile().maxMemoryMb();
    }

    private static <T> Optional<T> findByName(List<T> items, String name, Function<T, String> nameOf) {
        return items.stream().filter(item -> nameOf.apply(item).equals(name)).findFirst();
    }

    /** Best-effort persist — a failed save (e.g. read-only disk) shouldn't crash a selection change. */
    private void trySave() {
        try {
            save();
        } catch (IOException ignored) {
            // Change still applies for this run; it just won't stick next launch.
        }
    }

    public void save() throws IOException {
        Files.createDirectories(CONFIG_DIR);
        try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }

    public static LauncherConfig loadOrCreateDefault() throws IOException {
        if (!Files.exists(CONFIG_FILE)) {
            LauncherConfig defaults = new LauncherConfig();
            defaults.save();
            return defaults;
        }

        LauncherConfig loaded;
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
            loaded = GSON.fromJson(reader, LauncherConfig.class);
        }
        return loaded != null ? backfillDefaults(loaded) : new LauncherConfig();
    }

    /** Gson skips field initializers on deserialize, so fields absent from an older config.json land here as null. */
    private static LauncherConfig backfillDefaults(LauncherConfig config) {
        if (config.installDir == null || config.installDir.isBlank()) {
            config.installDir = new LauncherConfig().installDir;
        }
        if (config.playerName == null) {
            config.playerName = "";
        }
        if (config.servers == null || config.servers.isEmpty()) {
            config.servers = new ArrayList<>(List.of(ServerProfile.DEFAULT));
        } else {
            config.servers.replaceAll(LauncherConfig::withManagerDefaultsIfMissing);
        }
        if (config.activeServer == null) {
            config.activeServer = config.servers.get(0);
        } else {
            config.activeServer = withManagerDefaultsIfMissing(config.activeServer);
        }
        if (config.launchProfiles == null || config.launchProfiles.isEmpty()) {
            config.launchProfiles = new ArrayList<>(List.of(LaunchProfile.DEFAULT));
        } else {
            config.launchProfiles.replaceAll(LauncherConfig::withPlayerNameDefaultIfMissing);
        }
        if (config.selectedLaunchProfile == null || findByName(config.launchProfiles, config.selectedLaunchProfile, LaunchProfile::name).isEmpty()) {
            config.selectedLaunchProfile = config.launchProfiles.get(0).name();
        }
        // One-time migration: an older config.json's top-level playerName moves into whichever
        // launch profile is selected, if that profile doesn't already have its own — see
        // LaunchProfile's javadoc and the `playerName` field's own comment above.
        if (config.playerName != null && !config.playerName.isBlank()) {
            String legacyName = config.playerName;
            String selectedName = config.selectedLaunchProfile;
            config.launchProfiles.replaceAll(profile ->
                    profile.name().equals(selectedName) && profile.playerName().isBlank()
                            ? new LaunchProfile(profile.name(), legacyName, profile.minMemoryMb(), profile.maxMemoryMb())
                            : profile);
        }
        if (config.serverDirectory == null) {
            config.serverDirectory = "";
        }
        if (config.msaClientId == null) {
            config.msaClientId = "";
        }
        if (config.msaRefreshToken == null) {
            config.msaRefreshToken = "";
        }
        if (config.msaCachedUsername == null) {
            config.msaCachedUsername = "";
        }
        if (config.msaCachedUuid == null) {
            config.msaCachedUuid = "";
        }
        if (config.hiddenDetailProfiles == null) {
            config.hiddenDetailProfiles = new HashSet<>();
        }
        return config;
    }

    /**
     * Old launcher-config.json files predate the managerBaseUrl/managerApiKey
     * ServerProfile components; Gson leaves them null on deserialize since
     * records can't be patched in place. Rebuilds the record with
     * server-manager's own documented default port/localhost substituted in,
     * leaving every other field untouched.
     */
    private static ServerProfile withManagerDefaultsIfMissing(ServerProfile server) {
        if (server.managerBaseUrl() != null && !server.managerBaseUrl().isBlank()) {
            return server;
        }
        return new ServerProfile(
                server.name(), server.apiBaseUrl(), server.serverAddress(),
                server.minecraftVersion(), server.fabricLoaderVersion(), server.apiKey(),
                "http://localhost:7080", server.managerApiKey() == null ? "" : server.managerApiKey());
    }

    /** Old launcher-config.json files predate LaunchProfile's playerName component — see backfillDefaults. */
    private static LaunchProfile withPlayerNameDefaultIfMissing(LaunchProfile profile) {
        if (profile.playerName() != null) {
            return profile;
        }
        return new LaunchProfile(profile.name(), "", profile.minMemoryMb(), profile.maxMemoryMb());
    }
}
