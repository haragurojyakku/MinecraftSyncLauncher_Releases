package dev.haraguro.modserverplaymanager.servermanager.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.haraguro.modserverplaymanager.shared.model.BackupSettings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists backup policy (see {@link BackupSettings}) at
 * {@code <serverDir>/.mcsync-backup-settings.json} — same dotfile-JSON
 * pattern as sync-server's SyncServerSettingsService, kept out of
 * server.properties since retention/triggers/cooldown aren't Minecraft's
 * own config. Defaults on first run come from the same JVM properties
 * ServerManagerApp already reads for BackupService/BackupScheduler, so an
 * upgrade from before this file existed keeps behaving the same until
 * someone changes a setting via the GUI.
 */
public class BackupSettingsService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path settingsFile;
    private final BackupSettings defaults;
    private volatile BackupSettings settings;

    public BackupSettingsService(Path serverDir, BackupSettings defaults) {
        this.settingsFile = serverDir.resolve(".mcsync-backup-settings.json");
        this.defaults = defaults;
        this.settings = load();
    }

    public BackupSettings get() {
        return settings;
    }

    public synchronized BackupSettings update(BackupSettings newSettings) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        try (Writer writer = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
            GSON.toJson(newSettings, writer);
        }
        this.settings = newSettings;
        return settings;
    }

    private BackupSettings load() {
        if (!Files.exists(settingsFile)) {
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            BackupSettings loaded = GSON.fromJson(reader, BackupSettings.class);
            return loaded != null ? loaded : defaults;
        } catch (IOException e) {
            return defaults;
        }
    }
}
