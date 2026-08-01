package dev.haraguro.modserverplaymanager.launcher.manager;

import com.google.gson.Gson;
import dev.haraguro.modserverplaymanager.shared.model.BackupInfo;
import dev.haraguro.modserverplaymanager.shared.model.BackupSettings;
import dev.haraguro.modserverplaymanager.shared.model.ConnectionAttempt;
import dev.haraguro.modserverplaymanager.shared.model.ManagerStatus;
import dev.haraguro.modserverplaymanager.shared.model.PortForwardingStatus;
import dev.haraguro.modserverplaymanager.shared.model.ServerSettings;
import dev.haraguro.modserverplaymanager.shared.model.WhitelistEntry;
import dev.haraguro.modserverplaymanager.shared.model.WhitelistUpdateResult;
import dev.haraguro.modserverplaymanager.shared.model.WorldProfile;
import dev.haraguro.modserverplaymanager.shared.protocol.Routes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for server-manager's /api/manager/* routes, used by the
 * launcher GUI's Server Manager tab. server-manager runs as its own
 * separate headless process on the host machine; this class never touches
 * the supervised Minecraft server directly.
 */
public class ManagerClient {

    private final String managerBaseUrl;
    private final String managerApiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    public ManagerClient(String managerBaseUrl, String managerApiKey) {
        this.managerBaseUrl = managerBaseUrl.endsWith("/")
                ? managerBaseUrl.substring(0, managerBaseUrl.length() - 1)
                : managerBaseUrl;
        this.managerApiKey = managerApiKey;
    }

    public ManagerStatus getStatus() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_STATUS))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        return send(request, ManagerStatus.class, "fetch manager status");
    }

    public List<BackupInfo> listBackups() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_BACKUPS))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to list backups: HTTP " + response.statusCode() + " " + response.body());
        }
        BackupInfo[] backups = gson.fromJson(response.body(), BackupInfo[].class);
        return backups == null ? List.of() : List.of(backups);
    }

    /** Runs an on-demand backup now; may take a while (world zip), so callers should use a generous timeout. */
    public BackupInfo runBackup() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_BACKUP))
                        .timeout(Duration.ofMinutes(5)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, BackupInfo.class, "run backup");
    }

    public ManagerStatus stop() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_STOP))
                        .timeout(Duration.ofSeconds(35)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, ManagerStatus.class, "stop server");
    }

    /**
     * Starts the server if it's stopped, or restarts it if running — there is
     * no separate "start" route; server-manager's restart(grace) is a no-op
     * stop() (harmless if nothing is running) followed by start(). The GUI
     * exposes this as a single combined Start/Restart button.
     */
    public ManagerStatus startOrRestart() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_RESTART))
                        .timeout(Duration.ofSeconds(35)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, ManagerStatus.class, "start/restart server");
    }

    public ServerSettings getSettings() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_SETTINGS))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        return send(request, ServerSettings.class, "fetch server settings");
    }

    public ServerSettings updateSettings(ServerSettings settings) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_SETTINGS))
                        .timeout(Duration.ofSeconds(10)))
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(settings), StandardCharsets.UTF_8))
                .build();
        return send(request, ServerSettings.class, "update server settings");
    }

    /** Schedules a restart {@code delaySeconds} from now, with countdown chat announcements — see server-manager's ScheduledRestartService. */
    public ManagerStatus scheduleRestart(long delaySeconds) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_RESTART_SCHEDULE))
                        .timeout(Duration.ofSeconds(10)))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("delaySeconds", delaySeconds)), StandardCharsets.UTF_8))
                .build();
        return send(request, ManagerStatus.class, "schedule restart");
    }

    public ManagerStatus cancelScheduledRestart() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_RESTART_SCHEDULE_CANCEL))
                        .timeout(Duration.ofSeconds(10)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, ManagerStatus.class, "cancel scheduled restart");
    }

    public List<WhitelistEntry> listWhitelist() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_WHITELIST))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        return sendList(request, WhitelistEntry[].class, "fetch whitelist");
    }

    /**
     * Adds a whitelist entry — server-manager prefers applying this live via
     * RCON's own `whitelist add` (correct uuid whether the server is
     * online-mode or offline-mode) and only falls back to a direct file edit
     * (an offline-uuid guess) if the server isn't running; see
     * WhitelistUpdateResult#isAppliedLive to tell which happened.
     */
    public WhitelistUpdateResult addToWhitelist(String name) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_WHITELIST))
                        .timeout(Duration.ofSeconds(10)))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("name", name)), StandardCharsets.UTF_8))
                .build();
        return send(request, WhitelistUpdateResult.class, "add to whitelist");
    }

    public WhitelistUpdateResult removeFromWhitelist(String name) throws IOException, InterruptedException {
        String path = Routes.MANAGER_WHITELIST_ENTRY.replace("{name}", name);
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(10)))
                .DELETE()
                .build();
        return send(request, WhitelistUpdateResult.class, "remove from whitelist");
    }

    public List<WorldProfile> listWorlds() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_WORLDS))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        return sendList(request, WorldProfile[].class, "list world profiles");
    }

    /** cloneActive=true copies the currently active world's files; false creates an empty profile (a fresh world on next activate). */
    public WorldProfile createWorld(String name, boolean cloneActive) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_WORLDS))
                        .timeout(Duration.ofMinutes(5)))
                .POST(HttpRequest.BodyPublishers.ofString(
                        gson.toJson(Map.of("name", name, "cloneActive", cloneActive)), StandardCharsets.UTF_8))
                .build();
        return send(request, WorldProfile.class, "create world profile");
    }

    /** Stops/switches/restarts the server as needed — see server-manager's WorldProfileService#activate. */
    public ManagerStatus activateWorld(String name) throws IOException, InterruptedException {
        String path = Routes.MANAGER_WORLDS_ACTIVATE.replace("{name}", name);
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(35)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, ManagerStatus.class, "activate world profile");
    }

    public WorldProfile renameWorld(String name, String newName) throws IOException, InterruptedException {
        String path = Routes.MANAGER_WORLDS_RENAME.replace("{name}", name);
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(10)))
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("newName", newName)), StandardCharsets.UTF_8))
                .build();
        return send(request, WorldProfile.class, "rename world profile");
    }

    public List<WorldProfile> deleteWorld(String name) throws IOException, InterruptedException {
        String path = Routes.MANAGER_WORLDS_ENTRY.replace("{name}", name);
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofSeconds(10)))
                .DELETE()
                .build();
        return sendList(request, WorldProfile[].class, "delete world profile");
    }

    public BackupSettings getBackupSettings() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_BACKUP_SETTINGS))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        return send(request, BackupSettings.class, "fetch backup settings");
    }

    public BackupSettings updateBackupSettings(BackupSettings settings) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_BACKUP_SETTINGS))
                        .timeout(Duration.ofSeconds(10)))
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(settings), StandardCharsets.UTF_8))
                .build();
        return send(request, BackupSettings.class, "update backup settings");
    }

    /** Read-only — enabling/disabling the feature happens via the host-mode checkbox, not this client. */
    public PortForwardingStatus getPortForwardingStatus() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_PORT_FORWARDING_STATUS))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        return send(request, PortForwardingStatus.class, "fetch port forwarding status");
    }

    /** Most recent first — see server-manager's ConnectionLogParser for how these are detected. */
    public List<ConnectionAttempt> listConnectionAttempts() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.MANAGER_CONNECTION_ATTEMPTS))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        return sendList(request, ConnectionAttempt[].class, "fetch connection attempts");
    }

    private <T> T send(HttpRequest request, Class<T> type, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to " + action + ": HTTP " + response.statusCode() + " " + response.body());
        }
        return gson.fromJson(response.body(), type);
    }

    /** Same as send(), but for endpoints returning a JSON array — arrayType e.g. WhitelistEntry[].class. */
    private <T> List<T> sendList(HttpRequest request, Class<T[]> arrayType, String action) throws IOException, InterruptedException {
        T[] items = send(request, arrayType, action);
        return items == null ? List.of() : List.of(items);
    }

    private HttpRequest.Builder withApiKey(HttpRequest.Builder builder) {
        if (managerApiKey != null && !managerApiKey.isBlank()) {
            builder.header(Routes.API_KEY_HEADER, managerApiKey);
        }
        return builder;
    }

    private URI uri(String path) {
        return URI.create(managerBaseUrl + path);
    }
}
