package dev.haraguro.modserverplaymanager.launcher;

import dev.haraguro.modserverplaymanager.launcher.config.LauncherConfig;
import dev.haraguro.modserverplaymanager.launcher.launch.AuthSession;
import dev.haraguro.modserverplaymanager.launcher.launch.ConsoleProgressReporter;
import dev.haraguro.modserverplaymanager.launcher.launch.ErrorMessages;
import dev.haraguro.modserverplaymanager.launcher.launch.GameLauncher;
import dev.haraguro.modserverplaymanager.launcher.launch.OfflineAuth;
import dev.haraguro.modserverplaymanager.launcher.sync.ModSyncService;

public class Launcher {

    public static void main(String[] args) {
        try {
            LauncherConfig config = LauncherConfig.loadOrCreateDefault();
            System.out.println("mcsync launcher");
            System.out.println("  api server : " + config.getApiBaseUrl());
            System.out.println("  install dir: " + config.getInstallDir());
            System.out.println("  mc version : " + config.getMinecraftVersion() + " / fabric " + config.getFabricLoaderVersion());
            System.out.println("  auto-connect: " + (config.isAutoConnectEnabled() ? config.getServerAddress() : "disabled"));

            ModSyncService syncService = new ModSyncService(config.getApiBaseUrl(), config.getApiKey(), config.getInstallDir());
            ModSyncService.SyncResult result = syncService.sync(ConsoleProgressReporter::new);
            System.out.printf("Sync complete: %d change(s) applied (mc %s, fabric loader %s)%n",
                    result.changesApplied(),
                    result.manifest().getMinecraftVersion(),
                    result.manifest().getFabricLoaderVersion());

            // No Microsoft OAuth yet (see root README "Next steps") — launches
            // as an offline session, which only works against servers running
            // with online-mode=false.
            AuthSession auth = OfflineAuth.session(config.getPlayerName());

            // Assets (textures/sounds/lang) can be several hundred MB on first
            // run; skip with -Dmcsync.downloadAssets=false for a faster
            // iteration loop once you already have a populated assets/ dir.
            boolean downloadAssets = !"false".equalsIgnoreCase(System.getProperty("mcsync.downloadAssets"));

            System.out.println("Resolving launch profile and downloading client/libraries" + (downloadAssets ? "/assets" : "") + "...");
            Process process = new GameLauncher().launch(config, auth, downloadAssets, ConsoleProgressReporter::new);
            int exitCode = process.waitFor();
            System.out.println("Minecraft exited with code " + exitCode);
        } catch (Exception e) {
            System.err.println("Launcher failed: " + ErrorMessages.describe(e));
            System.exit(1);
        }
    }
}
