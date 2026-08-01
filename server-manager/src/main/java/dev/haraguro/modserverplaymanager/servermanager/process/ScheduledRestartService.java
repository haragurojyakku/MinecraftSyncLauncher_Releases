package dev.haraguro.modserverplaymanager.servermanager.process;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules a delayed server restart with countdown chat announcements (RCON
 * `say`), cancelable up until the moment it fires. Mirrors BackupScheduler's
 * "single dedicated ScheduledExecutorService" pattern rather than reusing a
 * shared pool, since a restart schedule needs to be cancelable as a unit —
 * an immediate restart doesn't go through here at all, it's just the
 * existing POST /api/manager/restart.
 */
public class ScheduledRestartService {

    /** Delay applied to on-demand ("immediate") restarts so players always get a chat warning. */
    public static final Duration IMMEDIATE_RESTART_DELAY = Duration.ofSeconds(30);

    // Countdown checkpoints before the restart at which an announcement fires —
    // only the ones that actually fit inside the requested delay are used.
    private static final List<Duration> MINUTE_CHECKPOINTS =
            List.of(Duration.ofMinutes(30), Duration.ofMinutes(15), Duration.ofMinutes(10),
                    Duration.ofMinutes(5), Duration.ofMinutes(4), Duration.ofMinutes(3),
                    Duration.ofMinutes(2), Duration.ofMinutes(1));
    private static final List<Duration> SECOND_CHECKPOINTS =
            List.of(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(5),
                    Duration.ofSeconds(4), Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1));

    private final ServerProcessSupervisor supervisor;
    private final Duration stopGracePeriod;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "restart-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    private final List<ScheduledFuture<?>> pendingFutures = new ArrayList<>();
    private volatile long pendingRestartAtMillis = 0;

    public ScheduledRestartService(ServerProcessSupervisor supervisor, Duration stopGracePeriod) {
        this.supervisor = supervisor;
        this.stopGracePeriod = stopGracePeriod;
    }

    /** Replaces any previously pending schedule. Announces the full delay immediately, then at each checkpoint. */
    public synchronized void scheduleRestart(Duration delay) {
        cancelScheduledRestart();

        for (Duration checkpoint : MINUTE_CHECKPOINTS) {
            if (checkpoint.compareTo(delay) < 0) {
                scheduleAnnouncement(delay, checkpoint);
            }
        }
        for (Duration checkpoint : SECOND_CHECKPOINTS) {
            if (checkpoint.compareTo(delay) < 0) {
                scheduleAnnouncement(delay, checkpoint);
            }
        }

        pendingFutures.add(executor.schedule(this::runScheduledRestart, delay.toMillis(), TimeUnit.MILLISECONDS));
        pendingRestartAtMillis = System.currentTimeMillis() + delay.toMillis();
        supervisor.broadcastSay("Server will restart in " + formatDuration(delay) + ".");
    }

    public synchronized void cancelScheduledRestart() {
        for (ScheduledFuture<?> future : pendingFutures) {
            future.cancel(false);
        }
        pendingFutures.clear();
        pendingRestartAtMillis = 0;
    }

    /** Epoch millis of the pending restart, or 0 if none is scheduled. */
    public long getPendingRestartAtMillis() {
        return pendingRestartAtMillis;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void scheduleAnnouncement(Duration delay, Duration remaining) {
        long fireAfterMillis = delay.minus(remaining).toMillis();
        pendingFutures.add(executor.schedule(
                () -> supervisor.broadcastSay("Server will restart in " + formatDuration(remaining) + "."),
                fireAfterMillis, TimeUnit.MILLISECONDS));
    }

    private void runScheduledRestart() {
        try {
            supervisor.broadcastSay("Server is restarting now.");
            supervisor.restart(stopGracePeriod);
        } catch (IOException e) {
            System.out.println("server-manager: scheduled restart failed: " + e.getMessage());
        } finally {
            synchronized (this) {
                pendingFutures.clear();
                pendingRestartAtMillis = 0;
            }
        }
    }

    private static String formatDuration(Duration d) {
        if (d.toMinutes() >= 1 && d.toSecondsPart() == 0) {
            return d.toMinutes() + (d.toMinutes() == 1 ? " minute" : " minutes");
        }
        if (d.toMinutes() >= 1) {
            return d.toMinutes() + "m " + d.toSecondsPart() + "s";
        }
        return d.toSeconds() + (d.toSeconds() == 1 ? " second" : " seconds");
    }
}
