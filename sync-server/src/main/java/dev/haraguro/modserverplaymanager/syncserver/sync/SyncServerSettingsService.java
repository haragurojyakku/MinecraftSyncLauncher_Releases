package dev.haraguro.modserverplaymanager.syncserver.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.haraguro.modserverplaymanager.shared.model.SyncServerSettings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists sync-server's own admin-facing behavior toggles (see
 * {@link SyncServerSettings}) at {@code <serverDir>/.mcsync-sync-settings.json}
 * — deliberately not server.properties, which is Minecraft's own config and
 * not this project's place to add custom keys into. A dotfile so
 * ServerFilesService.listManaged() (which already skips names starting with
 * ".") never confuses it for a mod/resource pack.
 */
public class SyncServerSettingsService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path settingsFile;
    private volatile SyncServerSettings settings;

    public SyncServerSettingsService(Path serverDir) {
        this.settingsFile = serverDir.resolve(".mcsync-sync-settings.json");
        this.settings = load();
    }

    public SyncServerSettings get() {
        return settings;
    }

    public synchronized SyncServerSettings update(SyncServerSettings newSettings) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        try (Writer writer = Files.newBufferedWriter(settingsFile, StandardCharsets.UTF_8)) {
            GSON.toJson(newSettings, writer);
        }
        this.settings = newSettings;
        return settings;
    }

    private SyncServerSettings load() {
        if (!Files.exists(settingsFile)) {
            return new SyncServerSettings(false);
        }
        try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            SyncServerSettings loaded = GSON.fromJson(reader, SyncServerSettings.class);
            return loaded != null ? loaded : new SyncServerSettings(false);
        } catch (IOException e) {
            return new SyncServerSettings(false);
        }
    }
}
