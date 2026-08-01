package dev.haraguro.modserverplaymanager.servermanager.backup;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Runs a backup in response to a player joining/leaving (or, in the future,
 * an in-game "please back up now" command — see
 * BackupSettings.allowParticipantTriggeredBackup and
 * Routes.MANAGER_BACKUP_PARTICIPANT_REQUEST) instead of only on
 * BackupScheduler's fixed timer or an explicit Backup Now click.
 *
 * All three trigger kinds share one cooldown clock (BackupSettings'
 * loginLogoutCooldownSeconds): a burst of joins/leaves — or a player mashing
 * a hypothetical in-game backup command — takes at most one backup per
 * cooldown window instead of one per event, which is the whole point of the
 * cooldown existing.
 */
public class BackupTriggerService {

    private final BackupService backupService;
    private final BackupSettingsService settingsService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "backup-trigger");
        thread.setDaemon(true);
        return thread;
    });

    private volatile long lastTriggeredAtMillis = 0;

    public BackupTriggerService(BackupService backupService, BackupSettingsService settingsService) {
        this.backupService = backupService;
        this.settingsService = settingsService;
    }

    public void onPlayerJoined() {
        if (settingsService.get().isBackupOnLogin()) {
            tryTrigger("login");
        }
    }

    public void onPlayerLeft() {
        if (settingsService.get().isBackupOnLogout()) {
            tryTrigger("logout");
        }
    }

    /** @return true if a backup was actually started; false if refused (disabled) or absorbed by the cooldown. */
    public boolean requestParticipantBackup() {
        if (!settingsService.get().isAllowParticipantTriggeredBackup()) {
            return false;
        }
        return tryTrigger("participant request");
    }

    private synchronized boolean tryTrigger(String reason) {
        long cooldownMillis = Math.max(0, settingsService.get().getLoginLogoutCooldownSeconds()) * 1000L;
        long now = System.currentTimeMillis();
        if (now - lastTriggeredAtMillis < cooldownMillis) {
            System.out.println("server-manager: skipping " + reason + "-triggered backup (still within cooldown)");
            return false;
        }
        lastTriggeredAtMillis = now;
        executor.submit(() -> {
            try {
                backupService.runBackup();
                System.out.println("server-manager: " + reason + "-triggered backup completed");
            } catch (Exception e) {
                System.out.println("server-manager: " + reason + "-triggered backup failed: " + e.getMessage());
            }
        });
        return true;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
