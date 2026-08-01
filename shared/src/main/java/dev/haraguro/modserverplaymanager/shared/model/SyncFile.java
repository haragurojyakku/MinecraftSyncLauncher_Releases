package dev.haraguro.modserverplaymanager.shared.model;

/**
 * One file inside the server's mods/ or config/ folder, as advertised by
 * sync-server's sync manifest and fetched by the launcher.
 */
public class SyncFile {

    private String fileName;
    private long size;
    private String sha1;

    public SyncFile() {
    }

    public SyncFile(String fileName, long size, String sha1) {
        this.fileName = fileName;
        this.size = size;
        this.sha1 = sha1;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSize() {
        return size;
    }

    public String getSha1() {
        return sha1;
    }
}
