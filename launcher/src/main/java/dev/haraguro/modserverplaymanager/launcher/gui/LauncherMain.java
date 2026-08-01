package dev.haraguro.modserverplaymanager.launcher.gui;

import dev.haraguro.modserverplaymanager.launcher.manager.LocalServiceLauncher;
import dev.haraguro.modserverplaymanager.servermanager.ServerManagerApp;
import dev.haraguro.modserverplaymanager.syncserver.SyncServerApp;
import javafx.application.Application;

import java.io.IOException;
import java.util.Arrays;

/**
 * The packaged app's actual entry point (see jpackageAppImage's
 * --main-class). Deliberately does NOT extend Application: a classpath
 * (non-modular) main class that directly extends javafx.application.Application
 * gets refused at startup with "Error: JavaFX runtime components are
 * missing, and are required to run this application" — this indirection
 * class sidesteps that check.
 *
 * Also doubles as server-manager's and sync-server's entry point when
 * started with {@link LocalServiceLauncher#SERVER_MANAGER_MODE_ARG} or
 * {@link LocalServiceLauncher#SYNC_SERVER_MODE_ARG} as the first argument —
 * see LocalServiceLauncher, which re-invokes this same packaged exe that way
 * for the launcher's "host this server on this PC" checkbox. This keeps the
 * distributable to a single executable instead of separate service exes
 * sitting next to it.
 */
public class LauncherMain {

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && LocalServiceLauncher.SERVER_MANAGER_MODE_ARG.equals(args[0])) {
            ServerManagerApp.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && LocalServiceLauncher.SYNC_SERVER_MODE_ARG.equals(args[0])) {
            SyncServerApp.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Application.launch(LauncherApp.class, args);
    }
}
