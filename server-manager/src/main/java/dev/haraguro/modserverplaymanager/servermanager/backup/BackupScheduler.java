package dev.haraguro.modserverplaymanager.servermanager.backup;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thin ScheduledExecutorService wrapper running BackupService.runBackup()
 * on a fixed interval — the codebase has no existing Timer/scheduler usage
 * anywhere else, so this deliberately doesn't reach for a cron library.
 * {@link #reconfigure} lets BackupSettings changes (periodic on/off, the
 * interval itself) take effect immediately rather than only on the next
 * server-manager restart, unlike server.properties-backed settings.
 */
public class BackupScheduler {

    private final BackupService backupService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "backup-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean enabled;
    private volatile Duration interval;

    public BackupScheduler(BackupService backupService, boolean enabled, Duration interval) {
        this.backupService = backupService;
        this.enabled = enabled;
        this.interval = interval;
    }

    public synchronized void start() {
        if (enabled) {
            scheduleNext();
        }
    }

    public synchronized void stop() {
        executor.shutdownNow();
    }

    /** Applies a new enabled/interval combination immediately, replacing any currently pending run. */
    public synchronized void reconfigure(boolean enabled, Duration interval) {
        this.enabled = enabled;
        this.interval = interval;
        scheduleNext();
    }

    private void scheduleNext() {
        if (!enabled) {
            System.out.println("server-manager: periodic backups disabled");
            return;
        }
        long minutes = Math.max(1, interval.toMinutes());
        executor.schedule(this::runAndReschedule, minutes, TimeUnit.MINUTES);
        System.out.println("server-manager: next periodic backup in " + minutes + " minutes");
    }

    private void runAndReschedule() {
        try {
            backupService.runBackup();
            System.out.println("server-manager: scheduled backup completed");
        } catch (Exception e) {
            // A single failed scheduled run must not kill the recurring schedule.
            System.out.println("server-manager: scheduled backup failed: " + e.getMessage());
        }
        synchronized (this) {
            scheduleNext();
        }
    }
}
