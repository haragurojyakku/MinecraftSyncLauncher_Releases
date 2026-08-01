package dev.haraguro.modserverplaymanager.launcher.manager;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Launches server-manager AND sync-server as background child processes,
 * for the launcher's "host this server on this PC" checkbox — both read
 * from the same local server/ folder, so hosting one without the other
 * isn't a useful state. {@link LauncherMain} dispatches to
 * {@code ServerManagerApp}/{@code SyncServerApp} when re-invoked with
 * {@link #SERVER_MANAGER_MODE_ARG}/{@link #SYNC_SERVER_MODE_ARG} as the
 * first argument, so this just spawns the launcher's own packaged exe again
 * with that flag — there is no separate service exe/app image to find.
 *
 * The native launcher exe does NOT forward arbitrary {@code -D} flags from
 * its own command line to the JVM (confirmed empirically — they're silently
 * ignored). Instead, this passes the desired {@code -D} flags via the
 * standard {@code JAVA_TOOL_OPTIONS} environment variable, which every JVM
 * picks up at startup regardless of how it was launched.
 *
 * Spawned processes are NOT tied to this launcher's lifetime: on Windows, a
 * plain child process started via ProcessBuilder keeps running even if this
 * (parent) process exits or is killed — which is the point, closing the
 * launcher shouldn't kill the Minecraft server.
 */
public class LocalServiceLauncher {

    private static final String LAUNCHER_EXE_NAME = "ModServerPlayManagerByHaraguro.exe";

    /** First argv token that switches the packaged exe into server-manager's headless mode — see LauncherMain. */
    public static final String SERVER_MANAGER_MODE_ARG = "--server-manager";

    /** First argv token that switches the packaged exe into sync-server's headless mode — see LauncherMain. */
    public static final String SYNC_SERVER_MODE_ARG = "--sync-server";

    /** False for `:launcher:run`/IDE debug (no real installed app image to re-invoke). */
    public static boolean isSupported() {
        return currentLauncherExePath().isPresent();
    }

    /**
     * @param serverDirectory        absolute path to the local server/ folder, or blank to let
     *                               server-manager fall back to its own ../server default
     * @param managerBaseUrl         the active ServerProfile's managerBaseUrl — its port is reused
     *                               so the spawned instance matches what ManagerClient will call
     * @param managerApiKey          the active ServerProfile's managerApiKey, or blank for no auth
     * @param portForwardingEnabled  whether server-manager should try to open the Minecraft/sync-server
     *                               ports on the router automatically via UPnP (opt-in, see the
     *                               "Open ports automatically" checkbox)
     * @param syncServerApiBaseUrl   the active ServerProfile's apiBaseUrl — its port is what gets
     *                               mapped as the sync-server port when portForwardingEnabled
     * @param syncServerApiKey       the active ServerProfile's apiKey — only its blank/non-blank
     *                               state is forwarded (never the secret itself), since server-manager
     *                               refuses to map an unauthenticated sync-server port to the internet
     */
    public Process launchServerManager(String serverDirectory, String managerBaseUrl, String managerApiKey,
                                        boolean portForwardingEnabled, String syncServerApiBaseUrl, String syncServerApiKey) throws IOException {
        StringBuilder javaToolOptions = new StringBuilder();
        int port = parsePort(managerBaseUrl);
        if (port > 0) {
            javaToolOptions.append("-Dmcsync.manager.port=").append(port).append(' ');
        }
        if (managerApiKey != null && !managerApiKey.isBlank()) {
            javaToolOptions.append("-Dmcsync.manager.api.key=").append(managerApiKey).append(' ');
        }
        javaToolOptions.append("-Dmcsync.portforward.enabled=").append(portForwardingEnabled).append(' ');
        int syncServerPort = parsePort(syncServerApiBaseUrl);
        if (syncServerPort > 0) {
            javaToolOptions.append("-Dmcsync.portforward.syncserver.port=").append(syncServerPort).append(' ');
        }
        boolean syncServerApiKeySet = syncServerApiKey != null && !syncServerApiKey.isBlank();
        javaToolOptions.append("-Dmcsync.portforward.syncserver.apikeyset=").append(syncServerApiKeySet).append(' ');
        return spawn(SERVER_MANAGER_MODE_ARG, serverDirectory, javaToolOptions);
    }

    /**
     * @param serverDirectory absolute path to the local server/ folder, or blank to let
     *                        sync-server fall back to its own ../server default
     * @param apiBaseUrl      the active ServerProfile's apiBaseUrl — its port is reused so
     *                        the spawned instance matches what the launcher's own sync/admin
     *                        clients will call
     * @param apiKey          the active ServerProfile's apiKey, or blank for no auth
     */
    public Process launchSyncServer(String serverDirectory, String apiBaseUrl, String apiKey) throws IOException {
        StringBuilder javaToolOptions = new StringBuilder();
        int port = parsePort(apiBaseUrl);
        if (port > 0) {
            javaToolOptions.append("-Dmcsync.port=").append(port).append(' ');
        }
        if (apiKey != null && !apiKey.isBlank()) {
            javaToolOptions.append("-Dmcsync.api.key=").append(apiKey).append(' ');
        }
        return spawn(SYNC_SERVER_MODE_ARG, serverDirectory, javaToolOptions);
    }

    private Process spawn(String modeArg, String serverDirectory, StringBuilder javaToolOptions) throws IOException {
        Path launcherExe = currentLauncherExePath()
                .orElseThrow(() -> new IllegalStateException(
                        "Not running as " + LAUNCHER_EXE_NAME + " — host mode needs the packaged app, not :launcher:run/IDE debug."));

        if (serverDirectory != null && !serverDirectory.isBlank()) {
            javaToolOptions.append("-Dmcsync.server.dir=").append(serverDirectory).append(' ');
        }

        Path logFile = logFilePath(modeArg);
        Files.createDirectories(logFile.getParent());

        ProcessBuilder builder = new ProcessBuilder(launcherExe.toString(), modeArg);
        builder.directory(launcherExe.getParent().toFile());
        builder.environment().put("JAVA_TOOL_OPTIONS", javaToolOptions.toString().trim());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    /** Where an auto-launched service's stdout/stderr is captured — surfaced in the GUI on launch failure. */
    public static Path logFilePath(String modeArg) {
        String name = SYNC_SERVER_MODE_ARG.equals(modeArg) ? "hosted-sync-server.log" : "hosted-server-manager.log";
        return Path.of(System.getProperty("user.home"), ".modserverplaymanager", name);
    }

    private static Optional<Path> currentLauncherExePath() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(LAUNCHER_EXE_NAME));
    }

    private static int parsePort(String baseUrl) {
        try {
            return URI.create(baseUrl).getPort();
        } catch (Exception e) {
            return -1;
        }
    }
}
