package dev.haraguro.modserverplaymanager.shared.model;

/**
 * One completed world backup archive, as listed by server-manager's
 * /api/manager/backups endpoint.
 */
public class BackupInfo {

    private String fileName;
    private long sizeBytes;
    private long createdAtMillis;

    public BackupInfo() {
    }

    public BackupInfo(String fileName, long sizeBytes, long createdAtMillis) {
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.createdAtMillis = createdAtMillis;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }
}
