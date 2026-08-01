package dev.haraguro.modserverplaymanager.syncserver.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.haraguro.modserverplaymanager.shared.model.ModCategory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists per-mod {@link ModCategory} overrides at
 * {@code <modsDir>/.mcsync-categories.json} — a dotfile so
 * ServerFilesService.listManaged() (which already skips names starting with
 * ".") never confuses it for a mod. Only entries that aren't the default
 * ({@link ModCategory#BOTH}) are stored, keyed by the mod's base file name
 * (never the ".disabled" form, so toggling enabled/disabled doesn't lose the
 * classification).
 */
public class ModCategoryStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, ModCategory>>() {
    }.getType();

    private final Path storeFile;
    private volatile Map<String, ModCategory> categories;

    public ModCategoryStore(Path dir) {
        this.storeFile = dir.resolve(".mcsync-categories.json");
        this.categories = load();
    }

    public ModCategory get(String fileName) {
        ModCategory category = categories.get(fileName);
        return category != null ? category : ModCategory.BOTH;
    }

    public synchronized void set(String fileName, ModCategory category) throws IOException {
        if (category == null || category == ModCategory.BOTH) {
            remove(fileName);
            return;
        }
        Map<String, ModCategory> updated = new LinkedHashMap<>(categories);
        updated.put(fileName, category);
        save(updated);
    }

    public synchronized void remove(String fileName) throws IOException {
        if (!categories.containsKey(fileName)) {
            return;
        }
        Map<String, ModCategory> updated = new LinkedHashMap<>(categories);
        updated.remove(fileName);
        save(updated);
    }

    private void save(Map<String, ModCategory> updated) throws IOException {
        Files.createDirectories(storeFile.getParent());
        try (Writer writer = Files.newBufferedWriter(storeFile, StandardCharsets.UTF_8)) {
            GSON.toJson(updated, writer);
        }
        this.categories = updated;
    }

    private Map<String, ModCategory> load() {
        if (!Files.exists(storeFile)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
            Map<String, ModCategory> loaded = GSON.fromJson(reader, MAP_TYPE);
            return loaded != null ? loaded : Map.of();
        } catch (IOException e) {
            return Map.of();
        }
    }
}
