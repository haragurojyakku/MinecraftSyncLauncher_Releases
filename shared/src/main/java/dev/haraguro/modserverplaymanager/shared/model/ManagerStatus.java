package dev.haraguro.modserverplaymanager.shared.model;

import java.util.List;

/**
 * Live status of the supervised Fabric server process, as reported by
 * server-manager's /api/manager/status endpoint.
 *
 * state is a plain string ("STARTING"/"RUNNING"/"STOPPING"/"STOPPED"/
 * "CRASHED") rather than a Java enum, so an older/newer client doesn't
 * hard-fail deserializing a value it doesn't recognize yet.
 *
 * This is the intended landing spot for future resource-monitoring fields
 * (tps, cpuLoad, memoryUsedMb) once that work is scoped — deliberately not
 * added speculatively here.
 */
public class ManagerStatus {

    private String state;
    private long uptimeMillis;
    private long pid;
    private long lastBackupAtMillis;
    private int crashRestartCount;
    private List<String> onlinePlayers;
    private long pendingRestartAtMillis;
    private String lastCrashReason;

    public ManagerStatus() {
    }

    public ManagerStatus(String state, long uptimeMillis, long pid, long lastBackupAtMillis, int crashRestartCount,
                          List<String> onlinePlayers, long pendingRestartAtMillis, String lastCrashReason) {
        this.state = state;
        this.uptimeMillis = uptimeMillis;
        this.pid = pid;
        this.lastBackupAtMillis = lastBackupAtMillis;
        this.crashRestartCount = crashRestartCount;
        this.onlinePlayers = onlinePlayers;
        this.pendingRestartAtMillis = pendingRestartAtMillis;
        this.lastCrashReason = lastCrashReason;
    }

    public String getState() {
        return state;
    }

    public long getUptimeMillis() {
        return uptimeMillis;
    }

    public long getPid() {
        return pid;
    }

    public long getLastBackupAtMillis() {
        return lastBackupAtMillis;
    }

    public int getCrashRestartCount() {
        return crashRestartCount;
    }

    public List<String> getOnlinePlayers() {
        return onlinePlayers;
    }

    /** Epoch millis of a pending scheduled restart, or 0 if none is scheduled. */
    public long getPendingRestartAtMillis() {
        return pendingRestartAtMillis;
    }

    /** Tail of the server's own console output as of the most recent unexpected exit, or null if it hasn't crashed. */
    public String getLastCrashReason() {
        return lastCrashReason;
    }
}
