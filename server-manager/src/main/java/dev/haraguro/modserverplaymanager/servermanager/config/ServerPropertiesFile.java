package dev.haraguro.modserverplaymanager.servermanager.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads/rewrites server/server.properties as flat key=value lines, keeping
 * unrelated lines (comments, blanks, keys we don't touch) exactly as they
 * were and in their original order. This is the single source of truth for
 * server config — server-manager deliberately doesn't invent a second
 * config store for RCON settings.
 */
public class ServerPropertiesFile {

    private final Path path;
    private final List<String> lines;
    private final Map<String, Integer> keyLineIndex;

    private ServerPropertiesFile(Path path, List<String> lines, Map<String, Integer> keyLineIndex) {
        this.path = path;
        this.lines = lines;
        this.keyLineIndex = keyLineIndex;
    }

    public static ServerPropertiesFile load(Path path) throws IOException {
        List<String> lines = Files.exists(path)
                ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                : new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            index.put(trimmed.substring(0, eq).strip(), i);
        }
        return new ServerPropertiesFile(path, lines, index);
    }

    public Optional<String> get(String key) {
        Integer lineIndex = keyLineIndex.get(key);
        if (lineIndex == null) {
            return Optional.empty();
        }
        String line = lines.get(lineIndex);
        return Optional.of(line.substring(line.indexOf('=') + 1));
    }

    public String getOrDefault(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    public void set(String key, String value) {
        String newLine = key + "=" + value;
        Integer lineIndex = keyLineIndex.get(key);
        if (lineIndex != null) {
            lines.set(lineIndex, newLine);
        } else {
            lines.add(newLine);
            keyLineIndex.put(key, lines.size() - 1);
        }
    }

    public void save() throws IOException {
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /** The world folder name this server actually uses — don't hardcode "world". */
    public String getLevelName() {
        return getOrDefault("level-name", "world");
    }

    /**
     * Ensures enable-rcon/rcon.port/rcon.password are set, generating a
     * password via SecureRandom if one isn't already present — the same
     * 24-byte URL-safe-base64 recipe the launcher GUI's API-key "Generate"
     * button uses, just applied at the file layer since server-manager has
     * no GUI. Idempotent: once valid values exist, later calls change
     * nothing. Must run before the child process is first spawned — RCON
     * settings can't be hot-reloaded into an already-running server.
     *
     * @return the effective rcon.password (existing or newly generated)
     */
    public String ensureRconEnabled(int port) throws IOException {
        boolean changed = false;

        if (!"true".equals(getOrDefault("enable-rcon", "false"))) {
            set("enable-rcon", "true");
            changed = true;
        }
        if (!String.valueOf(port).equals(getOrDefault("rcon.port", ""))) {
            set("rcon.port", String.valueOf(port));
            changed = true;
        }
        String password = getOrDefault("rcon.password", "");
        if (password.isBlank()) {
            password = generatePassword();
            set("rcon.password", password);
            changed = true;
        }

        if (changed) {
            save();
        }
        return password;
    }

    private static String generatePassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
