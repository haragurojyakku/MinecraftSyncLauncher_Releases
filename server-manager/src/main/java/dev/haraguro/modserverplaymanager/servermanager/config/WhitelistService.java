package dev.haraguro.modserverplaymanager.servermanager.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.haraguro.modserverplaymanager.shared.model.WhitelistEntry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads/writes server/whitelist.json directly — the same file Minecraft
 * itself reads at startup and on "whitelist reload". Every server this
 * project manages runs offline-mode (see launcher's OfflineAuth), so a new
 * entry's uuid is always the deterministic offline UUID, never looked up
 * against Mojang. This class only ever touches the file; applying an edit
 * to an already-running server is ServerProcessSupervisor.reloadWhitelistIfRunning()'s
 * job via RCON.
 */
public class WhitelistService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path whitelistFile;

    public WhitelistService(Path whitelistFile) {
        this.whitelistFile = whitelistFile;
    }

    public synchronized List<WhitelistEntry> list() throws IOException {
        return load();
    }

    /** Adds `name`, replacing any existing entry with that name (e.g. if it was added under a stale uuid derivation). */
    public synchronized List<WhitelistEntry> add(String name) throws IOException {
        List<WhitelistEntry> entries = load();
        entries.removeIf(entry -> name.equals(entry.getName()));
        entries.add(new WhitelistEntry(offlineUuid(name), name));
        save(entries);
        return entries;
    }

    public synchronized List<WhitelistEntry> remove(String name) throws IOException {
        List<WhitelistEntry> entries = load();
        entries.removeIf(entry -> name.equals(entry.getName()));
        save(entries);
        return entries;
    }

    private List<WhitelistEntry> load() throws IOException {
        if (!Files.isRegularFile(whitelistFile)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(whitelistFile, StandardCharsets.UTF_8)) {
            WhitelistEntry[] entries = GSON.fromJson(reader, WhitelistEntry[].class);
            return entries == null ? new ArrayList<>() : new ArrayList<>(List.of(entries));
        }
    }

    private void save(List<WhitelistEntry> entries) throws IOException {
        Files.createDirectories(whitelistFile.getParent());
        try (Writer writer = Files.newBufferedWriter(whitelistFile, StandardCharsets.UTF_8)) {
            GSON.toJson(entries, writer);
        }
    }

    /** Same offline-UUID derivation as launcher's OfflineAuth/WhitelistGenerator — vanilla's own cracked/LAN-play formula. */
    private static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
