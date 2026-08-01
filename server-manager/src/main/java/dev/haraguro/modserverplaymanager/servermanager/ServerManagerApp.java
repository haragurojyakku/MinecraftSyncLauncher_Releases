package dev.haraguro.modserverplaymanager.servermanager;

import dev.haraguro.modserverplaymanager.servermanager.backup.BackupScheduler;
import dev.haraguro.modserverplaymanager.servermanager.backup.BackupService;
import dev.haraguro.modserverplaymanager.servermanager.backup.BackupSettingsService;
import dev.haraguro.modserverplaymanager.servermanager.backup.BackupTriggerService;
import dev.haraguro.modserverplaymanager.servermanager.config.ServerPropertiesFile;
import dev.haraguro.modserverplaymanager.servermanager.config.ServerSettingsService;
import dev.haraguro.modserverplaymanager.servermanager.config.WhitelistService;
import dev.haraguro.modserverplaymanager.servermanager.network.PortForwardingService;
import dev.haraguro.modserverplaymanager.servermanager.process.ScheduledRestartService;
import dev.haraguro.modserverplaymanager.servermanager.process.ServerProcessSupervisor;
import dev.haraguro.modserverplaymanager.servermanager.routes.ManagerRoutes;
import dev.haraguro.modserverplaymanager.servermanager.worlds.WorldProfileService;
import dev.haraguro.modserverplaymanager.shared.model.BackupSettings;
import io.javalin.Javalin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

public class ServerManagerApp {

    public static void main(String[] args) throws IOException {
        int port = Integer.getInteger("mcsync.manager.port", 7080);
        // Defaults to the sibling server/ runtime environment, same as sync-server.
        Path serverDir = Path.of(System.getProperty("mcsync.server.dir", "../server"));
        String apiKey = System.getProperty("mcsync.manager.api.key");
        boolean autostart = Boolean.parseBoolean(System.getProperty("mcsync.manager.autostart", "true"));

        int rconPort = Integer.getInteger("mcsync.rcon.port", 25575);
        boolean rconAutoconfigure = Boolean.parseBoolean(System.getProperty("mcsync.rcon.autoconfigure", "true"));

        String minMemory = System.getProperty("mcsync.server.jvm.minMemory", "1G");
        String maxMemory = System.getProperty("mcsync.server.jvm.maxMemory", "4G");

        int backupIntervalMinutes = Integer.getInteger("mcsync.backup.interval.minutes", 60);
        int backupRetentionCount = Integer.getInteger("mcsync.backup.retention.count", 10);
        String backupDirProperty = System.getProperty("mcsync.backup.dir");
        Path backupDir = backupDirProperty != null ? Path.of(backupDirProperty) : serverDir.resolve("backups");

        int crashMaxRestarts = Integer.getInteger("mcsync.manager.crash.maxRestarts", 5);
        int crashWindowMinutes = Integer.getInteger("mcsync.manager.crash.windowMinutes", 10);

        boolean portForwardingEnabled = Boolean.parseBoolean(System.getProperty("mcsync.portforward.enabled", "false"));
        int portForwardingSyncServerPort = Integer.getInteger("mcsync.portforward.syncserver.port", 0);
        boolean portForwardingSyncServerApiKeySet =
                Boolean.parseBoolean(System.getProperty("mcsync.portforward.syncserver.apikeyset", "false"));

        // RCON settings can't be hot-reloaded into an already-running server, so this must
        // happen before the child process is ever spawned below.
        Path serverPropertiesPath = serverDir.resolve("server.properties");
        ServerPropertiesFile serverProperties = ServerPropertiesFile.load(serverPropertiesPath);

        String rconPassword;
        if (rconAutoconfigure) {
            rconPassword = serverProperties.ensureRconEnabled(rconPort);
        } else {
            boolean rconEnabled = "true".equals(serverProperties.getOrDefault("enable-rcon", "false"));
            rconPassword = serverProperties.getOrDefault("rcon.password", "");
            if (!rconEnabled || rconPassword.isBlank()) {
                throw new IllegalStateException(
                        "RCON is not configured in " + serverPropertiesPath.toAbsolutePath()
                                + " and -Dmcsync.rcon.autoconfigure=false. Add enable-rcon=true, rcon.port="
                                + rconPort + " and a rcon.password by hand, or drop that flag to let "
                                + "server-manager configure it automatically.");
            }
        }

        Duration rconTimeout = Duration.ofSeconds(5);
        Duration stopGracePeriod = Duration.ofSeconds(30);
        Path logFile = Path.of("data", "console.log");

        // Defaults for a brand new .mcsync-backup-settings.json — an upgrade from before this
        // file existed keeps behaving exactly like today's fixed interval/retention until
        // someone actually changes a setting via the GUI's Backup tab.
        BackupSettings defaultBackupSettings = new BackupSettings(
                backupRetentionCount, false, false, true, backupIntervalMinutes, 300, false);
        BackupSettingsService backupSettingsService = new BackupSettingsService(serverDir, defaultBackupSettings);
        BackupSettings backupSettings = backupSettingsService.get();

        BackupService backupService = new BackupService(
                serverDir, backupDir, rconPort, rconPassword, rconTimeout, backupSettings.getRetentionCount());

        ServerProcessSupervisor supervisor = new ServerProcessSupervisor(
                serverDir, minMemory, maxMemory, rconPort, rconPassword, rconTimeout,
                crashMaxRestarts, Duration.ofMinutes(crashWindowMinutes), logFile,
                // Safety net: if a previous run died between save-off and save-on, re-enable
                // autosave as soon as the (possibly still-autosave-disabled) server reports booted.
                backupService::restoreAutosave);

        BackupScheduler backupScheduler = new BackupScheduler(backupService, backupSettings.isBackupPeriodic(),
                Duration.ofMinutes(backupSettings.getPeriodicIntervalMinutes()));
        BackupTriggerService backupTriggerService = new BackupTriggerService(backupService, backupSettingsService);
        supervisor.setOnPlayerJoined(backupTriggerService::onPlayerJoined);
        supervisor.setOnPlayerLeft(backupTriggerService::onPlayerLeft);

        ServerSettingsService settingsService = new ServerSettingsService(serverPropertiesPath);
        WhitelistService whitelistService = new WhitelistService(serverDir.resolve("whitelist.json"));
        ScheduledRestartService restartScheduler = new ScheduledRestartService(supervisor, stopGracePeriod);
        WorldProfileService worldProfileService = new WorldProfileService(serverDir, serverPropertiesPath, supervisor);

        int minecraftPort = parseInt(serverProperties.getOrDefault("server-port", "25565"), 25565);
        PortForwardingService portForwardingService = new PortForwardingService(
                portForwardingEnabled, minecraftPort, portForwardingSyncServerPort, portForwardingSyncServerApiKeySet);

        Javalin app = Javalin.create(config -> config.jsonMapper(new GsonJsonMapper()));
        ManagerApiKeyAuth.register(app, apiKey);
        new ManagerRoutes(supervisor, backupService, backupSettingsService, backupScheduler, backupTriggerService,
                settingsService, whitelistService, restartScheduler, stopGracePeriod, portForwardingService,
                worldProfileService).register(app);

        app.start(port);
        System.out.println("mcsync server-manager listening on :" + port + " (server dir: " + serverDir.toAbsolutePath() + ")");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("WARNING: no API key set (-Dmcsync.manager.api.key=...). Anyone who can reach this "
                    + "port can stop/restart the server and trigger backups. Fine for LAN-only use; set a key "
                    + "before exposing this to the internet.");
        }

        backupScheduler.start();
        portForwardingService.start();

        if (autostart) {
            supervisor.start();
        } else {
            System.out.println("mcsync.manager.autostart=false — server not started; use POST "
                    + dev.haraguro.modserverplaymanager.shared.protocol.Routes.MANAGER_RESTART + " to start it.");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            backupScheduler.stop();
            backupTriggerService.shutdown();
            restartScheduler.shutdown();
            portForwardingService.stop();
            supervisor.stop(stopGracePeriod);
            supervisor.shutdown();
        }, "server-manager-shutdown"));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
