package dev.haraguro.modserverplaymanager.servermanager.backup;

import dev.haraguro.modserverplaymanager.servermanager.config.ServerPropertiesFile;
import dev.haraguro.modserverplaymanager.servermanager.rcon.RconClient;
import dev.haraguro.modserverplaymanager.shared.model.BackupInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Takes RCON-safe world backups: save-off -> zip world/ + small config
 * files -> save-on (always, even if zipping fails), then prunes old
 * backups beyond the retention count. Writes to a .tmp name first and
 * renames only on success, so a half-written zip never shows up in
 * listings or counts toward retention.
 *
 * If RCON isn't reachable (e.g. the supervised server isn't running right
 * now), the backup still proceeds without the save-off/save-on safety net
 * rather than hard-failing — a manual/on-demand backup while the server
 * happens to be down is still useful.
 */
public class BackupService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> CONFIG_FILES_TO_INCLUDE = List.of(
            "server.properties", "whitelist.json", "ops.json", "banned-players.json", "banned-ips.json"
    );

    private final Path serverDir;
    private final Path backupDir;
    private final int rconPort;
    private final String rconPassword;
    private final Duration rconTimeout;
    private volatile int retentionCount;

    private volatile long lastBackupAtMillis = 0;

    public BackupService(Path serverDir, Path backupDir, int rconPort, String rconPassword,
                          Duration rconTimeout, int retentionCount) {
        this.serverDir = serverDir;
        this.backupDir = backupDir;
        this.rconPort = rconPort;
        this.rconPassword = rconPassword;
        this.rconTimeout = rconTimeout;
        this.retentionCount = retentionCount;
    }

    public synchronized BackupInfo runBackup() throws IOException {
        String levelName = ServerPropertiesFile.load(serverDir.resolve("server.properties")).getLevelName();
        Path levelDir = serverDir.resolve(levelName);
        if (!Files.isDirectory(levelDir)) {
            throw new IOException("world directory not found: " + levelDir + " (has the server been started yet?)");
        }

        Files.createDirectories(backupDir);
        String fileName = "backup-" + TIMESTAMP_FORMAT.format(LocalDateTime.now()) + ".zip";
        Path finalFile = backupDir.resolve(fileName);
        Path tempFile = backupDir.resolve(fileName + ".tmp");

        boolean rconOk = trySaveOff();
        try {
            writeZip(tempFile, levelDir, levelName);
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        } finally {
            if (rconOk) {
                restoreAutosave();
            }
        }

        Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        lastBackupAtMillis = System.currentTimeMillis();
        prune();

        return new BackupInfo(fileName, Files.size(finalFile), lastBackupAtMillis);
    }

    public List<BackupInfo> listBackups() throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(backupDir)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("backup-") && name.endsWith(".zip");
                    })
                    .map(BackupService::describe)
                    .sorted(Comparator.comparingLong(BackupInfo::getCreatedAtMillis).reversed())
                    .collect(Collectors.toList());
        }
    }

    public long getLastBackupAtMillis() {
        return lastBackupAtMillis;
    }

    /** Applied on the next prune() — an in-flight backup already in progress isn't affected. */
    public void setRetentionCount(int retentionCount) {
        this.retentionCount = retentionCount;
    }

    /**
     * Re-enables autosave via RCON. Also used as a boot-complete safety net in case a prior
     * run died mid-backup — but the vanilla server prints "Done" (which triggers that safety
     * net) BEFORE its RCON listener actually starts, so the very first connection attempt
     * right after boot routinely loses that race. Retry a few times with a short delay rather
     * than treating one failure as final.
     */
    public void restoreAutosave() {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try (RconClient rcon = RconClient.connectAndAuthenticate(rconPort, rconPassword, rconTimeout)) {
                rcon.execute("save-on");
                return;
            } catch (IOException e) {
                lastFailure = e;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        System.out.println("server-manager: WARNING failed to re-enable autosave via RCON after retries: "
                + (lastFailure != null ? lastFailure.getMessage() : "unknown")
                + " — autosave may still be OFF on the running server.");
    }

    private boolean trySaveOff() {
        try (RconClient rcon = RconClient.connectAndAuthenticate(rconPort, rconPassword, rconTimeout)) {
            rcon.execute("save-off");
            rcon.execute("save-all flush");
            return true;
        } catch (IOException e) {
            System.out.println("server-manager: RCON unavailable for backup safety (" + e.getMessage()
                    + ") — proceeding without save-off/save-on. Is the server running?");
            return false;
        }
    }

    private void prune() throws IOException {
        List<BackupInfo> backups = listBackups();
        if (backups.size() <= retentionCount) {
            return;
        }
        for (BackupInfo old : backups.subList(retentionCount, backups.size())) {
            Files.deleteIfExists(backupDir.resolve(old.getFileName()));
        }
    }

    private static void writeZip(Path tempFile, Path levelDir, String levelName) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            addDirectoryToZip(zip, levelDir, levelName);
            Path serverDir = levelDir.getParent();
            for (String configFile : CONFIG_FILES_TO_INCLUDE) {
                Path file = serverDir.resolve(configFile);
                if (Files.isRegularFile(file)) {
                    addFileToZip(zip, file, configFile);
                }
            }
        }
    }

    private static void addDirectoryToZip(ZipOutputStream zip, Path dir, String entryPrefix) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            // session.lock is held open by the running server for its entire lifetime (it's
            // how Minecraft stops two processes touching the same world) — same reason the
            // root .gitignore already excludes it as transient/regenerable, not backup-worthy.
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("session.lock"))
                    .collect(Collectors.toList());
            for (Path path : files) {
                String relative = entryPrefix + "/" + dir.relativize(path).toString().replace('\\', '/');
                try {
                    addFileToZip(zip, path, relative);
                } catch (IOException e) {
                    // Windows can briefly hold an exclusive lock on a file the server is
                    // actively writing even after save-off; skip it rather than aborting
                    // the whole backup over one file.
                    System.out.println("server-manager: WARNING skipping locked/unreadable file in backup: "
                            + path + " (" + e.getMessage() + ")");
                }
            }
        }
    }

    private static void addFileToZip(ZipOutputStream zip, Path file, String entryName) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static BackupInfo describe(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new BackupInfo(file.getFileName().toString(), attrs.size(), attrs.creationTime().toMillis());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
