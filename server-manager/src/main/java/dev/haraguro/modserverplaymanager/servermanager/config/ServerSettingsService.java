package dev.haraguro.modserverplaymanager.servermanager.config;

import dev.haraguro.modserverplaymanager.shared.model.ServerSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Maps the curated subset of server.properties the launcher GUI exposes
 * (see {@link ServerSettings}) onto {@link ServerPropertiesFile}. Changes
 * are written to disk immediately but only take effect on the next server
 * restart, since Minecraft reads server.properties once at startup — this
 * class doesn't know or care whether the server is currently running.
 */
public class ServerSettingsService {

    private static final Set<String> VALID_DIFFICULTIES = Set.of("peaceful", "easy", "normal", "hard");
    private static final Set<String> VALID_GAMEMODES = Set.of("survival", "creative", "adventure", "spectator");

    private final Path serverPropertiesPath;

    public ServerSettingsService(Path serverPropertiesPath) {
        this.serverPropertiesPath = serverPropertiesPath;
    }

    public ServerSettings load() throws IOException {
        ServerPropertiesFile props = ServerPropertiesFile.load(serverPropertiesPath);
        return new ServerSettings(
                props.getOrDefault("difficulty", "normal"),
                props.getOrDefault("gamemode", "survival"),
                props.getOrDefault("motd", ""),
                parseInt(props.getOrDefault("max-players", "20"), 20),
                Boolean.parseBoolean(props.getOrDefault("white-list", "false")),
                Boolean.parseBoolean(props.getOrDefault("hardcore", "false")),
                parseInt(props.getOrDefault("view-distance", "10"), 10),
                parseInt(props.getOrDefault("simulation-distance", "10"), 10),
                parseInt(props.getOrDefault("spawn-protection", "16"), 16),
                Boolean.parseBoolean(props.getOrDefault("allow-flight", "false")));
    }

    /** @throws IllegalArgumentException if difficulty/gamemode aren't one of vanilla's recognized values */
    public void apply(ServerSettings settings) throws IOException {
        if (!VALID_DIFFICULTIES.contains(settings.getDifficulty())) {
            throw new IllegalArgumentException("Invalid difficulty: " + settings.getDifficulty());
        }
        if (!VALID_GAMEMODES.contains(settings.getGamemode())) {
            throw new IllegalArgumentException("Invalid gamemode: " + settings.getGamemode());
        }

        ServerPropertiesFile props = ServerPropertiesFile.load(serverPropertiesPath);
        props.set("difficulty", settings.getDifficulty());
        props.set("gamemode", settings.getGamemode());
        props.set("motd", settings.getMotd());
        props.set("max-players", String.valueOf(settings.getMaxPlayers()));
        props.set("white-list", String.valueOf(settings.isWhitelist()));
        props.set("hardcore", String.valueOf(settings.isHardcore()));
        props.set("view-distance", String.valueOf(settings.getViewDistance()));
        props.set("simulation-distance", String.valueOf(settings.getSimulationDistance()));
        props.set("spawn-protection", String.valueOf(settings.getSpawnProtection()));
        props.set("allow-flight", String.valueOf(settings.isAllowFlight()));
        props.save();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
