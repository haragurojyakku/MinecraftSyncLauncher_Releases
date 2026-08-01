package dev.haraguro.modserverplaymanager.shared.model;

/**
 * server-manager's backup policy (as opposed to {@link ServerSettings},
 * which is curated server.properties) — how many past backups to keep, which
 * events trigger one, and whether players themselves are allowed to ask for
 * one. See server-manager's BackupSettingsService for persistence and
 * BackupTriggerService for how backupOnLogin/backupOnLogout/cooldownSeconds
 * are actually enforced.
 */
public class BackupSettings {

    private int retentionCount;
    private boolean backupOnLogin;
    private boolean backupOnLogout;
    private boolean backupPeriodic;
    private int periodicIntervalMinutes;
    private int loginLogoutCooldownSeconds;
    private boolean allowParticipantTriggeredBackup;

    public BackupSettings() {
    }

    public BackupSettings(int retentionCount, boolean backupOnLogin, boolean backupOnLogout, boolean backupPeriodic,
                           int periodicIntervalMinutes, int loginLogoutCooldownSeconds,
                           boolean allowParticipantTriggeredBackup) {
        this.retentionCount = retentionCount;
        this.backupOnLogin = backupOnLogin;
        this.backupOnLogout = backupOnLogout;
        this.backupPeriodic = backupPeriodic;
        this.periodicIntervalMinutes = periodicIntervalMinutes;
        this.loginLogoutCooldownSeconds = loginLogoutCooldownSeconds;
        this.allowParticipantTriggeredBackup = allowParticipantTriggeredBackup;
    }

    public int getRetentionCount() {
        return retentionCount;
    }

    public boolean isBackupOnLogin() {
        return backupOnLogin;
    }

    public boolean isBackupOnLogout() {
        return backupOnLogout;
    }

    public boolean isBackupPeriodic() {
        return backupPeriodic;
    }

    public int getPeriodicIntervalMinutes() {
        return periodicIntervalMinutes;
    }

    public int getLoginLogoutCooldownSeconds() {
        return loginLogoutCooldownSeconds;
    }

    public boolean isAllowParticipantTriggeredBackup() {
        return allowParticipantTriggeredBackup;
    }
}
