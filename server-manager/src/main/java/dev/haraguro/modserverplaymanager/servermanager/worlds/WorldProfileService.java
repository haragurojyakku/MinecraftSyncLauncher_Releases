package dev.haraguro.modserverplaymanager.servermanager.worlds;

import dev.haraguro.modserverplaymanager.servermanager.config.ServerPropertiesFile;
import dev.haraguro.modserverplaymanager.servermanager.process.ServerProcessSupervisor;
import dev.haraguro.modserverplaymanager.shared.model.WorldProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages world saves as sibling top-level directories under server/
 * (server/world, server/world-creative, ...), switched purely by rewriting
 * server.properties' level-name — never by copying. Copying only happens
 * when a profile is explicitly created as a clone. A directory counts as a
 * world profile if it contains a level.dat, OR the PROFILE_MARKER file this
 * service drops into a freshly-created blank profile (which has no level.dat
 * until Minecraft generates one on its first boot with that level-name) —
 * without the marker, a just-created blank profile would be invisible to
 * list() until after its first activation. list() rescans the filesystem
 * every call rather than keeping a separate registry (same "disk is truth"
 * approach as WhitelistService).
 *
 * create/delete/rename all require the server to not be alive (see
 * ServerProcessSupervisor#isAlive) — these are rare, deliberate actions, so
 * the caller is asked to stop the server first rather than this service
 * silently doing it. activate() is the one operation worth automating: it
 * stops-if-alive, swaps level-name, and restarts-if-it-was-alive, so
 * switching feels like a single click.
 */
public class WorldProfileService {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String LEVEL_DAT = "level.dat";
    private static final String PROFILE_MARKER = ".mcsync-world-profile";

    private final Path serverDir;
    private final Path serverPropertiesPath;
    private final ServerProcessSupervisor supervisor;

    public WorldProfileService(Path serverDir, Path serverPropertiesPath, ServerProcessSupervisor supervisor) {
        this.serverDir = serverDir;
        this.serverPropertiesPath = serverPropertiesPath;
        this.supervisor = supervisor;
    }

    public List<WorldProfile> list() throws IOException {
        String activeLevelName = loadProperties().getLevelName();
        List<WorldProfile> profiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(serverDir)) {
            for (Path dir : stream.filter(Files::isDirectory).collect(Collectors.toList())) {
                if (!isWorldProfileDir(dir)) {
                    continue;
                }
                String name = dir.getFileName().toString();
                profiles.add(new WorldProfile(name, directorySize(dir),
                        lastModified(dir), name.equals(activeLevelName)));
            }
        }
        profiles.sort(Comparator.comparing(WorldProfile::getName));
        return profiles;
    }

    /** @throws IllegalStateException if the server is running. @throws IllegalArgumentException on a bad/colliding name. */
    public synchronized WorldProfile create(String name, boolean cloneActive) throws IOException {
        requireStopped("create a new world profile");
        validateName(name);
        Path target = serverDir.resolve(name);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("A file or directory named '" + name + "' already exists.");
        }

        if (cloneActive) {
            Path source = serverDir.resolve(loadProperties().getLevelName());
            if (!Files.isDirectory(source)) {
                throw new IOException("Active world directory not found: " + source);
            }
            copyDirectory(source, target);
        } else {
            // No level.dat here — Minecraft generates a brand new world the first time this
            // profile is activated and the server boots, same as any other empty level-name.
            // The marker file is what makes this still show up in list() before that happens.
            Files.createDirectory(target);
            Files.createFile(target.resolve(PROFILE_MARKER));
        }

        return new WorldProfile(name, cloneActive ? directorySize(target) : 0, System.currentTimeMillis(), false);
    }

    /** @throws IllegalArgumentException if no such profile exists. */
    public synchronized void activate(String name, Duration stopGrace) throws IOException {
        if (!isWorldProfileDir(serverDir.resolve(name))) {
            throw new IllegalArgumentException("No world profile named '" + name + "'.");
        }

        boolean wasAlive = supervisor.isAlive();
        if (wasAlive) {
            supervisor.stop(stopGrace);
        }

        ServerPropertiesFile properties = loadProperties();
        properties.set("level-name", name);
        properties.save();

        if (wasAlive) {
            supervisor.start();
        }
    }

    /** @throws IllegalStateException if the server is running, or if name is the active profile. */
    public synchronized void delete(String name) throws IOException {
        requireStopped("delete a world profile");
        if (name.equals(loadProperties().getLevelName())) {
            throw new IllegalStateException("Cannot delete the currently active world profile — activate a different one first.");
        }
        Path target = serverDir.resolve(name);
        if (!isWorldProfileDir(target)) {
            throw new IllegalArgumentException("No world profile named '" + name + "'.");
        }
        deleteDirectory(target);
    }

    /** @throws IllegalStateException if the server is running. @throws IllegalArgumentException on a bad/colliding name. */
    public synchronized WorldProfile rename(String name, String newName) throws IOException {
        requireStopped("rename a world profile");
        validateName(newName);
        Path source = serverDir.resolve(name);
        if (!isWorldProfileDir(source)) {
            throw new IllegalArgumentException("No world profile named '" + name + "'.");
        }
        Path target = serverDir.resolve(newName);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("A file or directory named '" + newName + "' already exists.");
        }

        ServerPropertiesFile properties = loadProperties();
        boolean wasActive = name.equals(properties.getLevelName());

        Files.move(source, target);

        if (wasActive) {
            properties.set("level-name", newName);
            properties.save();
        }

        return new WorldProfile(newName, directorySize(target), lastModified(target), wasActive);
    }

    private void requireStopped(String action) {
        if (supervisor.isAlive()) {
            throw new IllegalStateException("Stop the server before you " + action + ".");
        }
    }

    private ServerPropertiesFile loadProperties() throws IOException {
        return ServerPropertiesFile.load(serverPropertiesPath);
    }

    private static boolean isWorldProfileDir(Path dir) {
        return Files.isRegularFile(dir.resolve(LEVEL_DAT)) || Files.isRegularFile(dir.resolve(PROFILE_MARKER));
    }

    /** Prefers level.dat's mtime (meaningfully updated by saves); falls back to the marker/directory for a not-yet-booted blank profile. */
    private static long lastModified(Path dir) throws IOException {
        Path levelDat = dir.resolve(LEVEL_DAT);
        Path reference = Files.isRegularFile(levelDat) ? levelDat : dir;
        return Files.getLastModifiedTime(reference).toMillis();
    }

    private static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "World profile name must be 1-64 characters of letters, digits, '_' or '-'.");
        }
    }

    private static long directorySize(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    // session.lock is held open by a running server for its entire lifetime —
                    // same reasoning as BackupService's zip walk.
                    .filter(path -> !path.getFileName().toString().equals("session.lock"))
                    .mapToLong(WorldProfileService::sizeOrZero)
                    .sum();
        }
    }

    private static long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.collect(Collectors.toList())) {
                if (path.getFileName().toString().equals("session.lock")) {
                    continue;
                }
                Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
