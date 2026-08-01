package dev.haraguro.modserverplaymanager.launcher.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tracks which base file names inside a synced folder (installDir/mods or
 * installDir/resourcepacks) the server has claimed ownership of at some
 * point — i.e. which ones this launcher has seen listed in a sync manifest
 * — as opposed to files the player dropped in themselves (personal
 * client-only mods/resource packs sync-server has never distributed).
 * Persisted at {@code <targetDir>/.mcsync-managed.json} so ModSyncService's
 * "delete local files the server no longer tracks" cleanup only ever
 * touches files it once installed on the server's behalf, never a player's
 * own files — see ModSyncService.reconcile.
 */
public class SyncedFileStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SET_TYPE = new TypeToken<Set<String>>() {
    }.getType();

    private final Path storeFile;
    private final Set<String> managed;

    public SyncedFileStore(Path targetDir) {
        this.storeFile = targetDir.resolve(".mcsync-managed.json");
        this.managed = new TreeSet<>(load());
    }

    public boolean isManaged(String fileName) {
        return managed.contains(fileName);
    }

    public void markManaged(String fileName) {
        managed.add(fileName);
    }

    /** Call once a managed file is actually deleted, so the name is free again for a player's own file later. */
    public void forget(String fileName) {
        managed.remove(fileName);
    }

    public void save() throws IOException {
        Files.createDirectories(storeFile.getParent());
        try (Writer writer = Files.newBufferedWriter(storeFile, StandardCharsets.UTF_8)) {
            GSON.toJson(managed, writer);
        }
    }

    private Set<String> load() {
        if (!Files.exists(storeFile)) {
            return new HashSet<>();
        }
        try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
            Set<String> loaded = GSON.fromJson(reader, SET_TYPE);
            return loaded != null ? loaded : new HashSet<>();
        } catch (IOException e) {
            return new HashSet<>();
        }
    }
}
