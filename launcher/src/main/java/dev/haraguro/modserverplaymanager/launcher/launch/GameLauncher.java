package dev.haraguro.modserverplaymanager.launcher.launch;

import dev.haraguro.modserverplaymanager.launcher.config.LauncherConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Top-level entry point for "actually start Minecraft": resolve the
 * Fabric+vanilla profile for the configured version, make sure the jars
 * (and optionally assets) are on disk, then spawn the game process. When
 * {@link LauncherConfig#isAutoConnectEnabled()}, the game is told to join
 * {@link LauncherConfig#getServerAddress()} via quick play as soon as it
 * boots, and the active server is written into servers.dat — no manual
 * "Multiplayer -> Add Server" step needed either way.
 */
public class GameLauncher {

    private final ProfileResolver profileResolver = new ProfileResolver();

    /** @param progressFactory builds a fresh listener per phase, e.g. {@code ConsoleProgressReporter::new} */
    public Process launch(LauncherConfig config, AuthSession auth, boolean downloadAssets,
                           Function<String, ProgressListener> progressFactory) throws IOException, InterruptedException {
        Set<String> features = config.isAutoConnectEnabled() ? Set.of("is_quick_play_multiplayer") : Set.of();
        MinecraftProfile profile = profileResolver.resolve(config.getMinecraftVersion(), config.getFabricLoaderVersion(), features);

        Path installDir = config.getInstallDir();
        GameInstaller installer = new GameInstaller(installDir);
        installer.ensureInstalled(profile, progressFactory.apply("client+libraries"));
        if (downloadAssets) {
            installer.ensureAssets(profile, progressFactory.apply("assets"));
        }

        if (config.isAutoConnectEnabled()) {
            ServersDatWriter.addOrUpdate(installDir, config.getActiveServer().name(), config.getServerAddress());
        }

        Files.createDirectories(installDir.resolve("natives").resolve(profile.id()));

        List<String> command = new LaunchCommandBuilder().build(profile, installDir, auth, config);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(installDir.toFile());
        processBuilder.inheritIO();
        return processBuilder.start();
    }
}
