package dev.haraguro.modserverplaymanager.servermanager.routes;

import dev.haraguro.modserverplaymanager.servermanager.backup.BackupScheduler;
import dev.haraguro.modserverplaymanager.servermanager.backup.BackupService;
import dev.haraguro.modserverplaymanager.servermanager.backup.BackupSettingsService;
import dev.haraguro.modserverplaymanager.servermanager.backup.BackupTriggerService;
import dev.haraguro.modserverplaymanager.servermanager.config.ServerSettingsService;
import dev.haraguro.modserverplaymanager.servermanager.config.WhitelistService;
import dev.haraguro.modserverplaymanager.servermanager.network.PortForwardingService;
import dev.haraguro.modserverplaymanager.servermanager.process.ProcessState;
import dev.haraguro.modserverplaymanager.servermanager.process.ScheduledRestartService;
import dev.haraguro.modserverplaymanager.servermanager.process.ServerProcessSupervisor;
import dev.haraguro.modserverplaymanager.servermanager.worlds.WorldProfileService;
import dev.haraguro.modserverplaymanager.shared.model.BackupInfo;
import dev.haraguro.modserverplaymanager.shared.model.BackupSettings;
import dev.haraguro.modserverplaymanager.shared.model.ManagerStatus;
import dev.haraguro.modserverplaymanager.shared.model.ServerSettings;
import dev.haraguro.modserverplaymanager.shared.model.WhitelistEntry;
import dev.haraguro.modserverplaymanager.shared.model.WhitelistUpdateResult;
import dev.haraguro.modserverplaymanager.shared.model.WorldProfile;
import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class ManagerRoutes {

    private final ServerProcessSupervisor supervisor;
    private final BackupService backupService;
    private final BackupSettingsService backupSettingsService;
    private final BackupScheduler backupScheduler;
    private final BackupTriggerService backupTriggerService;
    private final ServerSettingsService settingsService;
    private final WhitelistService whitelistService;
    private final ScheduledRestartService restartScheduler;
    private final Duration stopGracePeriod;
    private final PortForwardingService portForwardingService;
    private final WorldProfileService worldProfileService;

    public ManagerRoutes(ServerProcessSupervisor supervisor, BackupService backupService,
                          BackupSettingsService backupSettingsService, BackupScheduler backupScheduler,
                          BackupTriggerService backupTriggerService, ServerSettingsService settingsService,
                          WhitelistService whitelistService, ScheduledRestartService restartScheduler,
                          Duration stopGracePeriod, PortForwardingService portForwardingService,
                          WorldProfileService worldProfileService) {
        this.supervisor = supervisor;
        this.backupService = backupService;
        this.backupSettingsService = backupSettingsService;
        this.backupScheduler = backupScheduler;
        this.backupTriggerService = backupTriggerService;
        this.settingsService = settingsService;
        this.whitelistService = whitelistService;
        this.restartScheduler = restartScheduler;
        this.stopGracePeriod = stopGracePeriod;
        this.portForwardingService = portForwardingService;
        this.worldProfileService = worldProfileService;
    }

    public void register(Javalin app) {
        app.get(Routes.MANAGER_HEALTH, ctx -> ctx.result("ok"));

        app.get(Routes.MANAGER_STATUS, ctx -> ctx.json(currentStatus()));

        app.post(Routes.MANAGER_BACKUP, ctx -> {
            try {
                BackupInfo info = backupService.runBackup();
                ctx.json(info);
            } catch (IOException e) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of("error", e.getMessage()));
            }
        });

        app.get(Routes.MANAGER_BACKUPS, ctx -> {
            try {
                List<BackupInfo> backups = backupService.listBackups();
                ctx.json(backups);
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.post(Routes.MANAGER_STOP, ctx -> {
            supervisor.stop(stopGracePeriod);
            ctx.json(currentStatus());
        });

        app.post(Routes.MANAGER_RESTART, ctx -> {
            // A running server gets the same countdown-announced treatment as a scheduled
            // restart, just with a fixed short delay, so players always get a chat warning.
            // A stopped server is just being started — no players to warn, no delay.
            if (supervisor.getState() == ProcessState.RUNNING) {
                restartScheduler.scheduleRestart(ScheduledRestartService.IMMEDIATE_RESTART_DELAY);
                ctx.json(currentStatus());
                return;
            }
            try {
                supervisor.restart(stopGracePeriod);
                ctx.json(currentStatus());
            } catch (IOException e) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of("error", e.getMessage()));
            }
        });

        app.get(Routes.MANAGER_SETTINGS, ctx -> {
            try {
                ctx.json(settingsService.load());
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.put(Routes.MANAGER_SETTINGS, ctx -> {
            try {
                ServerSettings settings = ctx.bodyAsClass(ServerSettings.class);
                settingsService.apply(settings);
                ctx.json(settingsService.load());
            } catch (IllegalArgumentException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.post(Routes.MANAGER_RESTART_SCHEDULE, ctx -> {
            RestartScheduleRequest request = ctx.bodyAsClass(RestartScheduleRequest.class);
            if (request.delaySeconds() <= 0) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "delaySeconds must be positive"));
                return;
            }
            restartScheduler.scheduleRestart(Duration.ofSeconds(request.delaySeconds()));
            ctx.json(currentStatus());
        });

        app.post(Routes.MANAGER_RESTART_SCHEDULE_CANCEL, ctx -> {
            restartScheduler.cancelScheduledRestart();
            ctx.json(currentStatus());
        });

        app.get(Routes.MANAGER_WHITELIST, ctx -> {
            try {
                ctx.json(whitelistService.list());
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.post(Routes.MANAGER_WHITELIST, ctx -> {
            try {
                WhitelistAddRequest request = ctx.bodyAsClass(WhitelistAddRequest.class);
                if (request.name() == null || request.name().isBlank()) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "name must not be blank"));
                    return;
                }
                String name = request.name().trim();
                // Always write whitelist.json ourselves first (WhitelistService derives the
                // correct offline uuid — see its class doc) then ask a running server to pick
                // up the change via `whitelist reload`. Deliberately not vanilla's own
                // `whitelist add <name>`: that resolves uuid from the server's profile cache
                // (usercache.json), which can hold a stale uuid from before this project's
                // offline-mode switch and would silently lock the player back out.
                List<WhitelistEntry> entries = whitelistService.add(name);
                boolean appliedLive = supervisor.reloadWhitelistIfRunning();
                ctx.json(new WhitelistUpdateResult(entries, appliedLive));
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.delete(Routes.MANAGER_WHITELIST_ENTRY, ctx -> {
            try {
                String name = ctx.pathParam("name");
                List<WhitelistEntry> entries = whitelistService.remove(name);
                boolean appliedLive = supervisor.reloadWhitelistIfRunning();
                ctx.json(new WhitelistUpdateResult(entries, appliedLive));
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.get(Routes.MANAGER_CONNECTION_ATTEMPTS, ctx -> ctx.json(supervisor.recentConnectionAttempts()));

        app.get(Routes.MANAGER_PORT_FORWARDING_STATUS, ctx -> ctx.json(portForwardingService.getStatus()));

        app.get(Routes.MANAGER_BACKUP_SETTINGS, ctx -> ctx.json(backupSettingsService.get()));

        app.put(Routes.MANAGER_BACKUP_SETTINGS, ctx -> {
            try {
                BackupSettings settings = ctx.bodyAsClass(BackupSettings.class);
                BackupSettings saved = backupSettingsService.update(settings);
                backupService.setRetentionCount(saved.getRetentionCount());
                backupScheduler.reconfigure(saved.isBackupPeriodic(), Duration.ofMinutes(saved.getPeriodicIntervalMinutes()));
                ctx.json(saved);
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.get(Routes.MANAGER_WORLDS, ctx -> {
            try {
                ctx.json(worldProfileService.list());
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.post(Routes.MANAGER_WORLDS, ctx -> {
            try {
                CreateWorldRequest request = ctx.bodyAsClass(CreateWorldRequest.class);
                WorldProfile profile = worldProfileService.create(request.name(), request.cloneActive());
                ctx.json(profile);
            } catch (IllegalStateException e) {
                ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.post(Routes.MANAGER_WORLDS_ACTIVATE, ctx -> {
            try {
                worldProfileService.activate(ctx.pathParam("name"), stopGracePeriod);
                ctx.json(currentStatus());
            } catch (IllegalArgumentException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE).json(Map.of("error", e.getMessage()));
            }
        });

        app.post(Routes.MANAGER_WORLDS_RENAME, ctx -> {
            try {
                RenameWorldRequest request = ctx.bodyAsClass(RenameWorldRequest.class);
                WorldProfile profile = worldProfileService.rename(ctx.pathParam("name"), request.newName());
                ctx.json(profile);
            } catch (IllegalStateException e) {
                ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.delete(Routes.MANAGER_WORLDS_ENTRY, ctx -> {
            try {
                worldProfileService.delete(ctx.pathParam("name"));
                ctx.json(worldProfileService.list());
            } catch (IllegalStateException e) {
                ctx.status(HttpStatus.CONFLICT).json(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        // No manager-API-key requirement by design (see Routes.MANAGER_BACKUP_PARTICIPANT_REQUEST) —
        // gated purely by the allowParticipantTriggeredBackup toggle and the shared cooldown instead.
        app.post(Routes.MANAGER_BACKUP_PARTICIPANT_REQUEST, ctx -> {
            boolean started = backupTriggerService.requestParticipantBackup();
            if (!started) {
                ctx.status(HttpStatus.FORBIDDEN).json(Map.of("error",
                        "Participant-triggered backups are disabled, or one just ran (cooldown)."));
                return;
            }
            ctx.status(HttpStatus.ACCEPTED).json(Map.of("status", "started"));
        });
    }

    private record RestartScheduleRequest(long delaySeconds) {
    }

    private record WhitelistAddRequest(String name) {
    }

    private record CreateWorldRequest(String name, boolean cloneActive) {
    }

    private record RenameWorldRequest(String newName) {
    }

    private ManagerStatus currentStatus() {
        return new ManagerStatus(
                supervisor.getState().name(),
                supervisor.getUptimeMillis(),
                supervisor.getPid(),
                backupService.getLastBackupAtMillis(),
                supervisor.getCrashRestartCount(),
                supervisor.listOnlinePlayers(),
                restartScheduler.getPendingRestartAtMillis(),
                supervisor.getLastCrashReason()
        );
    }
}
