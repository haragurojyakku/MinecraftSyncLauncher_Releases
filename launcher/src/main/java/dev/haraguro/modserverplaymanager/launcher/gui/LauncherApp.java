package dev.haraguro.modserverplaymanager.launcher.gui;

import dev.haraguro.modserverplaymanager.launcher.admin.PlayerSkinClient;
import dev.haraguro.modserverplaymanager.launcher.admin.ServerFilesAdminClient;
import dev.haraguro.modserverplaymanager.launcher.auth.MinecraftAuthenticator;
import dev.haraguro.modserverplaymanager.launcher.auth.MsaAuthException;
import dev.haraguro.modserverplaymanager.launcher.config.ConnectionFileCrypto;
import dev.haraguro.modserverplaymanager.launcher.config.LaunchProfile;
import dev.haraguro.modserverplaymanager.launcher.config.LauncherConfig;
import dev.haraguro.modserverplaymanager.launcher.config.ServerProfile;
import dev.haraguro.modserverplaymanager.launcher.i18n.Lang;
import dev.haraguro.modserverplaymanager.launcher.i18n.Messages;
import dev.haraguro.modserverplaymanager.launcher.launch.AuthSession;
import dev.haraguro.modserverplaymanager.launcher.launch.ErrorMessages;
import dev.haraguro.modserverplaymanager.launcher.launch.GameLauncher;
import dev.haraguro.modserverplaymanager.launcher.launch.OfflineAuth;
import dev.haraguro.modserverplaymanager.launcher.manager.GameServerConnectivityChecker;
import dev.haraguro.modserverplaymanager.launcher.manager.LocalServiceLauncher;
import dev.haraguro.modserverplaymanager.launcher.manager.ManagerClient;
import dev.haraguro.modserverplaymanager.launcher.sync.ModSyncService;
import dev.haraguro.modserverplaymanager.launcher.update.AppVersion;
import dev.haraguro.modserverplaymanager.launcher.update.SelfUpdater;
import dev.haraguro.modserverplaymanager.launcher.update.UpdateChecker;
import dev.haraguro.modserverplaymanager.shared.model.BackupInfo;
import dev.haraguro.modserverplaymanager.shared.model.BackupSettings;
import dev.haraguro.modserverplaymanager.shared.model.ConnectionAttempt;
import dev.haraguro.modserverplaymanager.shared.model.ManagedFile;
import dev.haraguro.modserverplaymanager.shared.model.ManagerStatus;
import dev.haraguro.modserverplaymanager.shared.model.ModCategory;
import dev.haraguro.modserverplaymanager.shared.model.PortForwardingStatus;
import dev.haraguro.modserverplaymanager.shared.model.ServerSettings;
import dev.haraguro.modserverplaymanager.shared.model.SyncServerSettings;
import dev.haraguro.modserverplaymanager.shared.model.WhitelistEntry;
import dev.haraguro.modserverplaymanager.shared.model.WhitelistUpdateResult;
import dev.haraguro.modserverplaymanager.shared.model.WorldProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GUI front end: sync/update-check runs automatically on startup and
 * whenever the selected server changes; the player then explicitly presses
 * Update (if changes are pending) or Play (once up to date) — see
 * {@link LauncherMain} for why this class isn't the packaged app's entry
 * point directly.
 */
public class LauncherApp extends Application {

    private final LauncherWindow window = new LauncherWindow();
    // Single-thread so update-check/update/play never overlap; the window
    // also disables its controls during each so this is mostly a backstop.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "launcher-flow");
        t.setDaemon(true);
        return t;
    });
    // Separate from `worker` so manager actions (refresh/start/stop/backup)
    // serialize against each other (no Stop+Restart race) without blocking
    // or being blocked by the mod-sync flow above.
    private final ExecutorService managerWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "manager-actions");
        t.setDaemon(true);
        return t;
    });
    // Keeps the Server Manager tab live (state/uptime/online players/
    // whitelist/connection attempts) without the player needing to click
    // Refresh — see pollManagerStatusQuietly(). Every tick is submitted
    // through managerWorker (not run directly here) so it never overlaps a
    // user-triggered Start/Stop/Backup, and .get() blocks this poller
    // thread until that tick finishes before the next delay starts, so a
    // slow/hung fetch can't pile up queued polls.
    private final ScheduledExecutorService managerStatusPoller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "manager-status-poller");
        t.setDaemon(true);
        return t;
    });
    private static final Duration MANAGER_STATUS_POLL_INTERVAL = Duration.ofSeconds(5);
    // -1 = "haven't fetched a baseline yet" — otherwise a crash from a
    // previous launcher session would look like a brand new one on the
    // very first poll after startup.
    private volatile int lastKnownCrashCount = -1;
    // Logged once per newly-observed error, not once per poll tick — see fetchAndShowManagerStatus().
    private volatile String lastKnownPortForwardingError = null;

    private final LocalServiceLauncher localServiceLauncher = new LocalServiceLauncher();

    private LauncherConfig config;
    private ManagerClient managerClient;
    private ServerFilesAdminClient filesAdminClient;
    private PlayerSkinClient skinClient;
    // Cached so runUpdatePreventOverwrite/runUpdatePublicGameAddress can preserve whichever field
    // they're not touching — SyncServerSettings is one server-wide object, not two independent flags.
    private volatile SyncServerSettings lastSyncSettings = new SyncServerSettings(false, null);
    // The auto-launched server-manager/sync-server child processes (host
    // mode) — null/dead means "not hosting locally right now". Not tied to
    // this launcher's own lifetime: a plain child process on Windows keeps
    // running even if this (parent) process exits or is killed, which is
    // the point. Both are launched together since both read from the same
    // local server/ folder — see LocalServiceLauncher.
    private Process hostedServerManagerProcess;
    private Process hostedSyncServerProcess;
    // Lets the device-code prompt's Cancel button interrupt an in-flight
    // signInInteractive() poll loop, which would otherwise block `worker`
    // (and Play/Update with it) for up to the code's ~15 minute lifetime.
    private volatile Future<?> signInFuture;

    @Override
    public void start(Stage stage) throws Exception {
        config = LauncherConfig.loadOrCreateDefault();
        Messages.setLang(resolveInitialLang(config));
        managerClient = newManagerClient(config.getActiveServer());
        filesAdminClient = newFilesAdminClient(config.getActiveServer());
        skinClient = newSkinClient(config.getActiveServer());

        window.setOnLanguageChange(this::onLanguageChange);
        window.setOnInstallDirChange(dir -> {
            config.setInstallDir(dir);
            log(Messages.get("log.installDir", dir));
            worker.submit(this::checkForUpdates);
        });
        window.setOnConnectClick(server -> {
            config.setActiveServer(server);
            bindToProfile(server);
            Platform.runLater(() -> window.updateCurrentServerLabel(server));
            logServer(Messages.get("log.selectedServer", server.name()));
            worker.submit(this::checkForUpdates);
        });
        // Just picking a different saved profile (not yet Connecting to it) still points the
        // Player/Backup/Mod/Resource Pack/Settings tabs at it — see bindToProfile's javadoc.
        window.setOnViewProfileChange(this::bindToProfile);
        window.setOnSavePresetClick(config::saveServerPreset);
        window.setOnRenameProfileClick((oldName, renamed) -> {
            config.renameServerPreset(oldName, renamed);
            Platform.runLater(() -> window.refreshServerProfiles(config.getServers(), config.getActiveServer()));
            logServer(Messages.get("log.profileRenamed", oldName, renamed.name()));
        });
        window.setOnDeleteProfileClick(server -> {
            config.deleteServerPreset(server.name());
            Platform.runLater(() -> window.refreshServerProfiles(config.getServers(), config.getActiveServer()));
            logServer(Messages.get("log.profileDeleted", server.name()));
        });
        window.setOnSaveHostSettingsClick(this::onSaveHostSettings);
        window.setOnExportConnectionFileClick(this::runExportConnectionFile);
        window.setOnExportConnectionFileEncryptedClick(this::runExportConnectionFileEncrypted);
        window.setOnImportConnectionFileClick(this::runImportConnectionFile);
        window.setOnImportEncryptedConnectionFileClick(this::runImportEncryptedConnectionFile);
        window.setOnResetClick(() -> worker.submit(this::runReset));
        window.setOnLaunchProfileChange(profile -> config.selectLaunchProfile(profile.name()));
        window.setOnSaveLaunchProfileClick(profile -> {
            config.saveLaunchProfile(profile);
            config.selectLaunchProfile(profile.name());
            log(Messages.get("log.launchProfileSaved", profile.name()));
        });
        window.setOnRenameLaunchProfileClick((oldName, renamed) -> {
            config.renameLaunchProfile(oldName, renamed);
            Platform.runLater(() -> window.refreshLaunchProfiles(config.getLaunchProfiles(), config.getSelectedLaunchProfile()));
            log(Messages.get("log.launchProfileRenamed", oldName, renamed.name()));
        });
        window.setOnDeleteLaunchProfileClick(profile -> {
            config.deleteLaunchProfile(profile.name());
            Platform.runLater(() -> window.refreshLaunchProfiles(config.getLaunchProfiles(), config.getSelectedLaunchProfile()));
            log(Messages.get("log.launchProfileDeleted", profile.name()));
        });
        window.setOnExportLaunchProfileClick(this::runExportLaunchProfile);
        window.setOnImportLaunchProfileClick(this::runImportLaunchProfile);
        // Player name now lives on the selected LaunchProfile itself (see LaunchProfile's javadoc)
        // — setPlayerName replaces that profile in config's list, so the picker(s)' own item lists
        // need an explicit resync or they'd keep showing the pre-edit playerName next time around.
        window.setOnPlayerNameChange(name -> {
            config.setPlayerName(name);
            Platform.runLater(() -> window.refreshLaunchProfiles(config.getLaunchProfiles(), config.getSelectedLaunchProfile()));
        });
        window.setOnMsaClientIdChange(config::setMsaClientId);
        window.setOnSignInClick(() -> signInFuture = worker.submit(this::runMicrosoftSignIn));
        window.setOnSignOutClick(this::onSignOut);
        window.setOnCancelSignInClick(() -> {
            if (signInFuture != null) {
                signInFuture.cancel(true);
            }
        });
        window.setOnUpdateClick(() -> worker.submit(this::runUpdate));
        window.setOnPlayClick(() -> worker.submit(this::runPlay));
        window.setOnChangeSkinClick(file -> worker.submit(() -> runChangeSkin(file)));
        window.setOnUpdateNowClick(assetUrl -> worker.submit(() -> runSelfUpdate(assetUrl)));
        window.setOnCheckForUpdateClick(() -> worker.submit(this::runCheckForUpdateManually));
        window.setOnManagerRefreshClick(() -> managerWorker.submit(this::refreshManagerStatus));
        window.setOnManagerStartRestartClick(() -> managerWorker.submit(this::runManagerStartRestart));
        window.setOnManagerStopClick(() -> managerWorker.submit(this::runManagerStop));
        window.setOnManagerBackupNowClick(() -> managerWorker.submit(this::runManagerBackupNow));
        window.setOnAddWhitelistClick(name -> managerWorker.submit(() -> runAddWhitelist(name)));
        window.setOnRemoveWhitelistClick(name -> managerWorker.submit(() -> runRemoveWhitelist(name)));
        window.setOnCreateWorldClick((name, cloneActive) -> managerWorker.submit(() -> runCreateWorld(name, cloneActive)));
        window.setOnActivateWorldClick(name -> managerWorker.submit(() -> runActivateWorld(name)));
        window.setOnRenameWorldClick((name, newName) -> managerWorker.submit(() -> runRenameWorld(name, newName)));
        window.setOnDeleteWorldClick(name -> managerWorker.submit(() -> runDeleteWorld(name)));
        window.setOnHostModeChange(this::onHostModeChange);
        window.setOnPortForwardingChange(this::onPortForwardingChange);
        window.setOnServerDirectoryChange(config::setServerDirectory);
        window.setOnCloseRequest(this::handleCloseRequest);
        window.setOnAddModClick(files -> managerWorker.submit(() -> runAddMods(files)));
        window.setOnRemoveModClick(file -> managerWorker.submit(() -> runRemoveMod(file)));
        window.setOnToggleModClick(file -> managerWorker.submit(() -> runToggleMod(file)));
        window.setOnBulkToggleModsClick(files -> managerWorker.submit(() -> runBulkToggleMods(files)));
        window.setOnSetModCategoryClick((file, category) -> managerWorker.submit(() -> runSetModCategory(file, category)));
        window.setOnPreventOverwriteChange(enabled -> managerWorker.submit(() -> runUpdatePreventOverwrite(enabled)));
        window.setOnSavePublicGameAddressClick(address -> managerWorker.submit(() -> runUpdatePublicGameAddress(address)));
        window.setOnFetchPublicGameAddress(this::fetchPublicGameAddress);
        window.setOnAddResourcePackClick(files -> managerWorker.submit(() -> runAddResourcePacks(files)));
        window.setOnRemoveResourcePackClick(file -> managerWorker.submit(() -> runRemoveResourcePack(file)));
        window.setOnToggleResourcePackClick(file -> managerWorker.submit(() -> runToggleResourcePack(file)));
        window.setOnBulkToggleResourcePacksClick(files -> managerWorker.submit(() -> runBulkToggleResourcePacks(files)));
        window.setOnLoadSettingsClick(() -> managerWorker.submit(this::runLoadSettings));
        window.setOnSaveSettingsClick(settings -> managerWorker.submit(() -> runSaveSettings(settings)));
        window.setOnSaveBackupSettingsClick(settings -> managerWorker.submit(() -> runSaveBackupSettings(settings)));
        window.setOnScheduleRestartClick(delaySeconds -> managerWorker.submit(() -> runScheduleRestart(delaySeconds)));
        window.setOnCancelScheduledRestartClick(() -> managerWorker.submit(this::runCancelScheduledRestart));

        window.show(stage, config);
        window.setHiddenDetailProfileNames(config.getHiddenDetailProfiles());
        window.setUpdateButtonVisible(false);
        window.setPlayButtonVisible(false);
        window.setControlsDisabled(true);
        window.setHostModeSupported(LocalServiceLauncher.isSupported());

        log(Messages.get("log.selectedServer", config.getActiveServer().name()));
        log(Messages.get("log.installDir", config.getInstallDir()));
        logServer(Messages.get("log.selectedServer", config.getActiveServer().name()));

        worker.submit(this::checkForUpdates);
        checkForLauncherUpdate();
        managerWorker.submit(this::refreshModsAndResourcePacks);
        managerWorker.submit(this::runLoadSettings);
        managerWorker.submit(this::runLoadBackupSettings);
        managerWorker.submit(this::checkGameServerConnectable);
        if (config.isHostModeEnabled()) {
            managerWorker.submit(this::launchHostedService);
        } else {
            // Not host mode — still worth a status pull so the Server Manager
            // tab isn't blank until the player clicks Refresh (harmless no-op
            // failure log if managerBaseUrl isn't configured/reachable).
            managerWorker.submit(this::refreshManagerStatus);
        }
        managerStatusPoller.scheduleWithFixedDelay(() -> {
            try {
                managerWorker.submit(this::pollManagerStatusQuietly).get();
            } catch (Exception e) {
                // Best-effort — a single missed poll isn't worth surfacing.
            }
        }, MANAGER_STATUS_POLL_INTERVAL.toSeconds(), MANAGER_STATUS_POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private ManagerClient newManagerClient(ServerProfile server) {
        return new ManagerClient(server.managerBaseUrl(), server.managerApiKey());
    }

    private ServerFilesAdminClient newFilesAdminClient(ServerProfile server) {
        return new ServerFilesAdminClient(server.apiBaseUrl(), server.apiKey());
    }

    private PlayerSkinClient newSkinClient(ServerProfile server) {
        return new PlayerSkinClient(server.apiBaseUrl(), server.apiKey());
    }

    /**
     * Points the admin/query clients at {@code server} and re-fetches everything the
     * Player/Backup/Mod/Resource Pack/Settings tabs show — i.e. binds those tabs to this profile.
     * Shared by both Connect (which also commits to it as the active/launch server — see
     * onConnectClick) and just picking a different profile in either tab's picker (onViewProfileChange),
     * which shouldn't change what Play launches until the player explicitly Connects.
     */
    private void bindToProfile(ServerProfile server) {
        managerClient = newManagerClient(server);
        filesAdminClient = newFilesAdminClient(server);
        skinClient = newSkinClient(server);
        managerWorker.submit(this::refreshModsAndResourcePacks);
        managerWorker.submit(this::runLoadSettings);
        managerWorker.submit(this::runLoadBackupSettings);
        managerWorker.submit(this::refreshManagerStatus);
        managerWorker.submit(this::checkGameServerConnectable);
    }

    /**
     * Server Management tab's "Save" — same side effects as Connect (this PC's host mode
     * launches server-manager/sync-server using the active ServerProfile's fields, see
     * LocalServiceLauncher, so changing e.g. the API key here needs to reach the same place).
     * Also updates the Server tab's mirrored fields via applyConfig so both stay in sync.
     */
    private void onSaveHostSettings(ServerProfile server) {
        config.setActiveServer(server);
        config.saveServerPreset(server);
        bindToProfile(server);
        Platform.runLater(() -> {
            window.applyConfig(config);
            window.updateCurrentServerLabel(server);
        });
        logManager(Messages.get("manager.log.hostSettingsSaved"));
    }

    /**
     * Exports {@code server}'s connect-relevant fields (never managerBaseUrl/managerApiKey — that's
     * this PC's own admin access, not something to hand to a joining player) as a JSON file that
     * {@link #runImportConnectionFile} can turn straight back into a saved, ready-to-Connect profile.
     */
    private void runExportConnectionFile(ServerProfile server, Path destination) {
        try {
            Files.writeString(destination, connectOnlyJson(server));
            logManager(Messages.get("manager.log.connectionFileExported", destination));
        } catch (IOException e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        }
    }

    /**
     * Password-encrypted counterpart of {@link #runExportConnectionFile} — see ConnectionFileCrypto.
     * The importing side marks this profile hidden-details (see runImportEncryptedConnectionFile),
     * so encrypting only matters for protecting the file in transit/at rest; it doesn't change what
     * ends up usable locally once decrypted.
     */
    private void runExportConnectionFileEncrypted(LauncherWindow.ExportEncryptedRequest request) {
        try {
            String encrypted = ConnectionFileCrypto.encrypt(connectOnlyJson(request.server()), request.password().toCharArray());
            Files.writeString(request.destination(), encrypted);
            logManager(Messages.get("manager.log.connectionFileExported", request.destination()));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        }
    }

    /** The connect-relevant subset shared by both export flows — never managerBaseUrl/managerApiKey. */
    private static String connectOnlyJson(ServerProfile server) {
        ServerProfile connectOnly = new ServerProfile(server.name(), server.apiBaseUrl(), server.serverAddress(),
                server.minecraftVersion(), server.fabricLoaderVersion(), server.apiKey(), "", "");
        return new GsonBuilder().setPrettyPrinting().create().toJson(connectOnly);
    }

    /**
     * Client-side half of {@link #runExportConnectionFile} — adds the imported profile as a saved
     * preset and selects it (see LauncherWindow.selectServerProfile), without connecting to it
     * automatically; the player still presses Connect themselves once they've reviewed it.
     */
    private void runImportConnectionFile(Path source) {
        try {
            finishImport(normalizeImportedProfile(Files.readString(source)), false);
        } catch (Exception e) {
            logServer(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        }
    }

    /**
     * Encrypted counterpart — decrypts with {@code password} first, then imports exactly like
     * {@link #runImportConnectionFile}, except the resulting profile is marked hidden-details (see
     * LauncherConfig.markDetailsHidden): the Server tab keeps its sync URL/game address/API key out
     * of the UI from now on, even though they're used internally to actually connect.
     */
    private void runImportEncryptedConnectionFile(Path source, String password) {
        try {
            String plaintext = ConnectionFileCrypto.decrypt(Files.readString(source), password.toCharArray());
            finishImport(normalizeImportedProfile(plaintext), true);
        } catch (Exception e) {
            logServer(Messages.get("alert.importConnectionFile.wrongPassword"));
        }
    }

    private ServerProfile normalizeImportedProfile(String json) throws IOException {
        ServerProfile imported = new Gson().fromJson(json, ServerProfile.class);
        if (imported == null || imported.apiBaseUrl() == null || imported.serverAddress() == null) {
            throw new IOException("not a valid connection settings file");
        }
        return new ServerProfile(
                imported.name() == null || imported.name().isBlank() ? "Imported" : imported.name(),
                imported.apiBaseUrl(), imported.serverAddress(),
                imported.minecraftVersion() == null ? "" : imported.minecraftVersion(),
                imported.fabricLoaderVersion() == null ? "" : imported.fabricLoaderVersion(),
                imported.apiKey() == null ? "" : imported.apiKey(),
                ServerProfile.DEFAULT.managerBaseUrl(), "");
    }

    private void finishImport(ServerProfile normalized, boolean hideDetails) {
        config.saveServerPreset(normalized);
        if (hideDetails) {
            config.markDetailsHidden(normalized.name());
        }
        Platform.runLater(() -> {
            window.setHiddenDetailProfileNames(config.getHiddenDetailProfiles());
            window.selectServerProfile(normalized);
        });
        logServer(Messages.get("log.connectionFileImported", normalized.name()));
    }

    /**
     * Exports a whole LaunchProfile as plain JSON — unlike ServerProfile there's no admin secret to
     * strip out (just a name, offline player name, and memory settings), so no connect-only subset
     * and no encrypted variant.
     */
    private void runExportLaunchProfile(LaunchProfile profile, Path destination) {
        try {
            Files.writeString(destination, new GsonBuilder().setPrettyPrinting().create().toJson(profile));
            log(Messages.get("log.launchProfileExported", destination));
        } catch (IOException e) {
            log(Messages.get("log.failed", ErrorMessages.describe(e)));
        }
    }

    /** Adds the imported profile as a saved preset and selects it — see LauncherWindow.selectLaunchProfileEverywhere. */
    private void runImportLaunchProfile(Path source) {
        try {
            LaunchProfile imported = new Gson().fromJson(Files.readString(source), LaunchProfile.class);
            if (imported == null || imported.name() == null || imported.name().isBlank()) {
                throw new IOException("not a valid launch profile file");
            }
            LaunchProfile normalized = new LaunchProfile(imported.name(),
                    imported.playerName() == null ? "" : imported.playerName(),
                    imported.minMemoryMb(), imported.maxMemoryMb());
            config.saveLaunchProfile(normalized);
            config.selectLaunchProfile(normalized.name());
            Platform.runLater(() -> window.refreshLaunchProfiles(config.getLaunchProfiles(), normalized));
            log(Messages.get("log.launchProfileImported", normalized.name()));
        } catch (Exception e) {
            log(Messages.get("log.failed", ErrorMessages.describe(e)));
        }
    }

    private static final Duration GAME_SERVER_CHECK_TIMEOUT = Duration.ofSeconds(3);

    /**
     * The header's "Server: Online/Unreachable" indicator — a plain TCP connect attempt against
     * the active server's game address, run from this (connecting) side. Independent of
     * server-manager's own admin API (see ManagerClient/fetchAndShowManagerStatus): a joining
     * player normally has no manager key at all and may not even be able to reach the manager's
     * port, so tying "can I join" to the manager being reachable read as permanently unreachable
     * for them even when the actual Minecraft server was up and joinable.
     */
    private void checkGameServerConnectable() {
        boolean reachable = GameServerConnectivityChecker.isReachable(config.getServerAddress(), GAME_SERVER_CHECK_TIMEOUT);
        Platform.runLater(() -> window.setServerConnectable(reachable));
    }

    /**
     * Independent of the mod-sync worker (it's read-only and shouldn't
     * delay that flow) — just asks GitHub for the latest release and, if
     * newer than {@link dev.haraguro.modserverplaymanager.launcher.update.AppVersion#current()},
     * shows the banner. Never downloads or installs anything itself.
     */
    private void checkForLauncherUpdate() {
        Thread thread = new Thread(() -> {
            try {
                new UpdateChecker().checkForUpdate().ifPresent(update ->
                        Platform.runLater(() -> window.showLauncherUpdateAvailable(update.version(), update.releaseUrl(), update.assetUrl())));
            } catch (Exception e) {
                log(Messages.get("log.failed", ErrorMessages.describe(e)));
            }
        }, "launcher-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * User-triggered counterpart to {@link #checkForLauncherUpdate()} — that one stays silent
     * when already up to date (fine for a background startup check), but a button the user just
     * clicked needs to say *something* back either way, so this logs the "up to date"/error cases
     * checkForLauncherUpdate() doesn't bother with.
     */
    private void runCheckForUpdateManually() {
        if ("dev".equals(AppVersion.current())) {
            log(Messages.get("log.launcherUpdateCheckUnavailable"));
            return;
        }
        log(Messages.get("log.checkingForLauncherUpdate"));
        try {
            Optional<UpdateChecker.UpdateInfo> update = new UpdateChecker().checkForUpdate();
            if (update.isPresent()) {
                UpdateChecker.UpdateInfo info = update.get();
                Platform.runLater(() -> window.showLauncherUpdateAvailable(info.version(), info.releaseUrl(), info.assetUrl()));
            } else {
                log(Messages.get("log.launcherUpToDate"));
            }
        } catch (Exception e) {
            log(Messages.get("log.failed", ErrorMessages.describe(e)));
        }
    }

    private Lang resolveInitialLang(LauncherConfig config) {
        Lang saved = config.getLanguage() != null ? Lang.byCode(config.getLanguage()) : null;
        return saved != null ? saved : Lang.detectFromOs();
    }

    private void onLanguageChange(Lang lang) {
        Messages.setLang(lang);
        config.setLanguage(lang.code());
        window.refreshTexts();
    }

    private void checkForUpdates() {
        setControlsDisabled(true);
        status("status.checkingUpdates");
        try {
            ModSyncService syncService = new ModSyncService(config.getApiBaseUrl(), config.getApiKey(), config.getInstallDir());
            ModSyncService.PreviewResult preview = syncService.preview();
            if (preview.isUpToDate()) {
                status("status.upToDate");
                showButtons(false, true);
            } else {
                status("status.updateAvailable", preview.outOfDateCount());
                showButtons(true, false);
            }
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            log(Messages.get("log.failed", message));
            status("status.failed", message);
            showButtons(true, false); // let them retry via Update
        } finally {
            setControlsDisabled(false);
        }
    }

    /**
     * Resets install dir / active server / launch profile to defaults and
     * deletes locally synced mods/resourcepacks — a clean slate for
     * verifying the update flow actually redetects and redownloads
     * everything, and that cleanup actually removes the files.
     */
    private void runReset() {
        setControlsDisabled(true);
        try {
            config.resetToDefaults();
            new ModSyncService(config.getApiBaseUrl(), config.getApiKey(), config.getInstallDir()).cleanupSyncedFiles();
            log(Messages.get("log.reset"));
            Platform.runLater(() -> window.applyConfig(config));
            checkForUpdates();
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            log(Messages.get("log.failed", message));
            status("status.failed", message);
        } finally {
            setControlsDisabled(false);
        }
    }

    private void runUpdate() {
        setControlsDisabled(true);
        status("status.updating");
        try {
            ModSyncService syncService = new ModSyncService(config.getApiBaseUrl(), config.getApiKey(), config.getInstallDir());
            ModSyncService.SyncResult result = syncService.sync(label -> new GuiProgressReporter(label, window));
            log(Messages.get("log.syncComplete", result.changesApplied(),
                    result.manifest().getMinecraftVersion(), result.manifest().getFabricLoaderVersion()));
            status("status.updateComplete");
            showButtons(false, true);
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            log(Messages.get("log.failed", message));
            status("status.failed", message);
            showButtons(true, false);
        } finally {
            setControlsDisabled(false);
        }
    }

    private void runPlay() {
        if (!config.isMsaSignedIn() && (config.getPlayerName() == null || config.getPlayerName().isBlank())) {
            status("status.failed", Messages.get("error.playerNameBlank"));
            return;
        }
        setControlsDisabled(true);
        try {
            AuthSession auth = config.isMsaSignedIn() ? signInWithCachedMicrosoftAccount() : OfflineAuth.session(config.getPlayerName());
            boolean downloadAssets = !"false".equalsIgnoreCase(System.getProperty("mcsync.downloadAssets"));

            status("status.resolvingProfile");
            log(Messages.get("log.resolvingAndDownloading", downloadAssets ? Messages.get("log.assetsSuffix") : ""));
            Process process = new GameLauncher().launch(config, auth, downloadAssets, label -> new GuiProgressReporter(label, window));

            status("status.running");
            log(Messages.get("log.launchedWaiting"));
            int exitCode = process.waitFor();
            log(Messages.get("log.exited", exitCode));
            status("status.exited", exitCode);
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            log(Messages.get("log.failed", message));
            status("status.failed", message);
        } finally {
            setControlsDisabled(false);
        }
    }

    /** Uploads a skin PNG for whichever UUID Play would currently launch as — offline or cached MSA account. */
    private void runChangeSkin(Path file) {
        setControlsDisabled(true);
        try {
            UUID uuid = config.isMsaSignedIn()
                    ? UUID.fromString(config.getMsaCachedUuid())
                    : OfflineAuth.session(config.getPlayerName()).uuid();
            skinClient.uploadSkin(uuid, file);
            log(Messages.get("log.skin.changed"));
        } catch (Exception e) {
            log(Messages.get("log.skin.failed", ErrorMessages.describe(e)));
        } finally {
            setControlsDisabled(false);
        }
    }

    /**
     * Silently refreshes the cached Microsoft account for Play. If the
     * refresh token is no longer valid, clears the cached account (reverting
     * the Account section to offline) and rethrows — launching offline
     * instead against what might be an online-mode=true server would just
     * trade this clear error for a confusing "why can't I connect" one.
     */
    private AuthSession signInWithCachedMicrosoftAccount() throws Exception {
        try {
            MinecraftAuthenticator.AuthResult result =
                    new MinecraftAuthenticator().signInSilently(config.getMsaClientId(), config.getMsaRefreshToken());
            config.updateMsaRefreshToken(result.refreshToken());
            return result.session();
        } catch (MsaAuthException e) {
            config.clearMsaAccount();
            Platform.runLater(() -> window.updateAccountLabel(false, config.getPlayerName(), null));
            throw e;
        }
    }

    private void runMicrosoftSignIn() {
        setControlsDisabled(true);
        try {
            MinecraftAuthenticator.AuthResult result = new MinecraftAuthenticator().signInInteractive(config.getMsaClientId(),
                    prompt -> Platform.runLater(() -> window.showMsaDeviceCodePrompt(prompt.userCode(), prompt.verificationUri())));
            config.cacheMsaAccount(result.session().playerName(), result.session().uuid().toString(), result.refreshToken());
            String username = result.session().playerName();
            Platform.runLater(() -> window.updateAccountLabel(true, config.getPlayerName(), username));
            log(Messages.get("log.msaSignedIn", username));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log(Messages.get("log.msaSignInCancelled"));
        } catch (Exception e) {
            log(Messages.get("log.failed", ErrorMessages.describe(e)));
        } finally {
            Platform.runLater(window::hideMsaDeviceCodePrompt);
            setControlsDisabled(false);
        }
    }

    private void onSignOut() {
        config.clearMsaAccount();
        Platform.runLater(() -> window.updateAccountLabel(false, config.getPlayerName(), null));
        log(Messages.get("log.msaSignedOut"));
    }

    /**
     * Downloads+extracts the new app-image, hands off to the detached
     * relaunch helper, then exits — see {@link SelfUpdater}. No
     * {@code finally}-block re-enable on the success path: the app is
     * about to quit, so there's nothing left to re-enable.
     */
    private void runSelfUpdate(String assetUrl) {
        setControlsDisabled(true);
        status("status.selfUpdating");
        try {
            SelfUpdater updater = new SelfUpdater();
            var extracted = updater.downloadAndExtract(assetUrl, new GuiProgressReporter("launcherUpdate", window));
            updater.relaunchAndExit(extracted);
            Platform.exit();
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            log(Messages.get("log.failed", message));
            status("status.failed", message);
            setControlsDisabled(false);
        }
    }

    private void refreshManagerStatus() {
        setManagerControlsDisabled(true);
        managerStatus("manager.status.refreshing");
        checkGameServerConnectable(); // independent of the manager fetch below — see its own javadoc
        try {
            fetchAndShowManagerStatus();
            logManager(Messages.get("manager.log.refreshed"));
            managerStatus("manager.status.refreshed");
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            logManager(Messages.get("manager.log.failed", message));
            managerStatus("manager.status.failed", message);
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    /**
     * Background counterpart to refreshManagerStatus() — same fetch, but no
     * controls-disable flicker and no "refreshing.../refreshed" log spam
     * every {@link #MANAGER_STATUS_POLL_INTERVAL}. A failed tick (e.g. mid
     * restart, when server-manager is briefly unreachable) doesn't log —
     * the next tick a few seconds later will just try again. The header's
     * connectable check runs regardless of whether the manager fetch
     * succeeds — see checkGameServerConnectable()'s javadoc.
     */
    private void pollManagerStatusQuietly() {
        checkGameServerConnectable();
        try {
            fetchAndShowManagerStatus();
        } catch (Exception ignored) {
            // Next tick a few seconds later will just try again.
        }
    }

    /**
     * Shared fetch+display core. Also compares the freshly fetched crash
     * count against the last one seen and, if it went up, logs the
     * captured crash detail (server-manager's ServerProcessSupervisor
     * keeps the tail of the crashed process's own console output) — this
     * is what actually answers "why did it crash" instead of just "it
     * crashed N times", and fires exactly once per new crash regardless of
     * whether it's noticed via a manual refresh or a background poll tick.
     */
    private void fetchAndShowManagerStatus() throws Exception {
        ManagerStatus status = managerClient.getStatus();
        Platform.runLater(() -> window.showManagerStatus(status));
        if (lastKnownCrashCount >= 0 && status.getCrashRestartCount() > lastKnownCrashCount
                && status.getLastCrashReason() != null) {
            logManager(Messages.get("manager.log.crashDetail", status.getLastCrashReason()));
        }
        lastKnownCrashCount = status.getCrashRestartCount();

        List<BackupInfo> backups = managerClient.listBackups();
        Platform.runLater(() -> window.showBackups(backups));
        List<WhitelistEntry> whitelist = managerClient.listWhitelist();
        Platform.runLater(() -> window.showWhitelist(whitelist));
        List<WorldProfile> worlds = managerClient.listWorlds();
        Platform.runLater(() -> window.showWorlds(worlds));
        List<ConnectionAttempt> connectionAttempts = managerClient.listConnectionAttempts();
        Platform.runLater(() -> window.showConnectionAttempts(connectionAttempts));

        PortForwardingStatus portForwardingStatus = managerClient.getPortForwardingStatus();
        Platform.runLater(() -> window.showPortForwardingStatus(portForwardingStatus));
        String portForwardingError = portForwardingStatus.getLastError();
        if (portForwardingError != null && !portForwardingError.equals(lastKnownPortForwardingError)) {
            logManager(Messages.get("manager.log.portForwardingError", portForwardingError));
        }
        lastKnownPortForwardingError = portForwardingError;
    }

    private void runManagerStartRestart() {
        setManagerControlsDisabled(true);
        managerStatus("manager.status.startingRestarting");
        try {
            ManagerStatus status = managerClient.startOrRestart();
            Platform.runLater(() -> {
                window.showManagerStatus(status);
                window.setPendingChangesVisible(false); // a manual restart applies any pending mod/settings changes too
            });
            logManager(Messages.get("manager.log.startRestartRequested"));
            managerStatus("manager.status.refreshed");
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            logManager(Messages.get("manager.log.failed", message));
            managerStatus("manager.status.failed", message);
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runManagerStop() {
        setManagerControlsDisabled(true);
        managerStatus("manager.status.stopping");
        try {
            ManagerStatus status = managerClient.stop();
            Platform.runLater(() -> window.showManagerStatus(status));
            logManager(Messages.get("manager.log.stopRequested"));
            managerStatus("manager.status.refreshed");
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            logManager(Messages.get("manager.log.failed", message));
            managerStatus("manager.status.failed", message);
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runManagerBackupNow() {
        setManagerControlsDisabled(true);
        managerStatus("manager.status.backingUp");
        try {
            BackupInfo info = managerClient.runBackup();
            logManager(Messages.get("manager.log.backupComplete", info.getFileName()));
            refreshManagerStatus(); // same worker thread — re-pull status + backups so the new one shows up immediately
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            logManager(Messages.get("manager.log.failed", message));
            managerStatus("manager.status.failed", message);
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runAddWhitelist(String name) {
        setManagerControlsDisabled(true);
        try {
            WhitelistUpdateResult result = managerClient.addToWhitelist(name);
            Platform.runLater(() -> window.showWhitelist(result.getEntries()));
            logManager(Messages.get(result.isAppliedLive() ? "manager.log.whitelistAdded" : "manager.log.whitelistAddedOffline", name));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runRemoveWhitelist(String name) {
        setManagerControlsDisabled(true);
        try {
            WhitelistUpdateResult result = managerClient.removeFromWhitelist(name);
            Platform.runLater(() -> window.showWhitelist(result.getEntries()));
            logManager(Messages.get(result.isAppliedLive() ? "manager.log.whitelistRemoved" : "manager.log.whitelistRemovedOffline", name));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runCreateWorld(String name, boolean cloneActive) {
        setManagerControlsDisabled(true);
        try {
            managerClient.createWorld(name, cloneActive);
            refreshWorlds();
            logManager(Messages.get("manager.log.worldCreated", name));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    /** May stop/switch/restart the running server — see server-manager's WorldProfileService#activate. */
    private void runActivateWorld(String name) {
        setManagerControlsDisabled(true);
        managerStatus("manager.status.switchingWorld");
        try {
            managerClient.activateWorld(name);
            refreshManagerStatus(); // same worker thread — re-pulls status + worlds so the new active flag shows immediately
            logManager(Messages.get("manager.log.worldActivated", name));
        } catch (Exception e) {
            String message = ErrorMessages.describe(e);
            logManager(Messages.get("manager.log.failed", message));
            managerStatus("manager.status.failed", message);
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runRenameWorld(String name, String newName) {
        setManagerControlsDisabled(true);
        try {
            managerClient.renameWorld(name, newName);
            refreshWorlds();
            logManager(Messages.get("manager.log.worldRenamed", name, newName));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runDeleteWorld(String name) {
        setManagerControlsDisabled(true);
        try {
            List<WorldProfile> profiles = managerClient.deleteWorld(name);
            Platform.runLater(() -> window.showWorlds(profiles));
            logManager(Messages.get("manager.log.worldDeleted", name));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void refreshWorlds() throws Exception {
        List<WorldProfile> profiles = managerClient.listWorlds();
        Platform.runLater(() -> window.showWorlds(profiles));
    }

    private void refreshModsAndResourcePacks() {
        setModsControlsDisabled(true);
        try {
            List<ManagedFile> mods = filesAdminClient.listMods();
            List<ManagedFile> resourcePacks = filesAdminClient.listResourcePacks();
            SyncServerSettings syncSettings = filesAdminClient.getSyncSettings();
            lastSyncSettings = syncSettings;
            Platform.runLater(() -> {
                window.showMods(mods);
                window.showResourcePacks(resourcePacks);
                window.showSyncSettings(syncSettings);
            });
            logModsSettings(Messages.get("mods.log.loaded", mods.size(), resourcePacks.size()));
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runUpdatePreventOverwrite(boolean enabled) {
        setModsControlsDisabled(true);
        try {
            SyncServerSettings updated = filesAdminClient.updateSyncSettings(
                    new SyncServerSettings(enabled, lastSyncSettings.getPublicGameServerAddress()));
            lastSyncSettings = updated;
            Platform.runLater(() -> window.showSyncSettings(updated));
            logModsSettings(Messages.get(enabled ? "mods.log.preventOverwriteEnabled" : "mods.log.preventOverwriteDisabled"));
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    /**
     * Host-side half of the "fetch address" feature — see fetchPublicGameAddress() for the client
     * half. Lives on the Server Manager tab (see LauncherWindow's publicGameAddressRow), not the
     * Mod tab — it's a connection setting, not mod admin.
     */
    private void runUpdatePublicGameAddress(String address) {
        String normalized = address == null || address.isBlank() ? null : address;
        setManagerControlsDisabled(true);
        try {
            SyncServerSettings updated = filesAdminClient.updateSyncSettings(
                    new SyncServerSettings(lastSyncSettings.isPreventOverwriteOnUpload(), normalized));
            lastSyncSettings = updated;
            Platform.runLater(() -> window.showSyncSettings(updated));
            logManager(normalized == null
                    ? Messages.get("mods.log.publicGameAddressCleared")
                    : Messages.get("mods.log.publicGameAddressSaved", normalized));
        } catch (Exception e) {
            logManager(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    /**
     * Client-side half — the add-profile dialog's "fetch" button calls this with whatever
     * apiUrl/apiKey it currently has typed in (not necessarily the active profile, since this is
     * for a server the user hasn't added yet), so it builds its own short-lived client rather than
     * reusing {@link #filesAdminClient}. Always completes on the FX thread — see the field's
     * javadoc in LauncherWindow for why that's part of the contract.
     */
    private CompletableFuture<String> fetchPublicGameAddress(String apiUrl, String apiKey) {
        CompletableFuture<String> future = new CompletableFuture<>();
        worker.submit(() -> {
            try {
                ServerFilesAdminClient client = new ServerFilesAdminClient(apiUrl, apiKey);
                String fetchedAddress = client.getSyncSettings().getPublicGameServerAddress();
                Platform.runLater(() -> future.complete(fetchedAddress));
            } catch (Exception e) {
                Platform.runLater(() -> future.completeExceptionally(e));
            }
        });
        return future;
    }

    /** One failed upload doesn't stop the rest — each file logs its own success/failure. */
    private void runAddMods(List<Path> files) {
        setModsControlsDisabled(true);
        try {
            for (Path file : files) {
                try {
                    filesAdminClient.uploadMod(file);
                    logModsSettings(Messages.get("mods.log.added", file.getFileName().toString()));
                } catch (Exception e) {
                    logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
                }
            }
            refreshModsAndResourcePacks();
            markPendingChanges();
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runRemoveMod(ManagedFile file) {
        setModsControlsDisabled(true);
        try {
            filesAdminClient.deleteMod(file.getFileName());
            logModsSettings(Messages.get("mods.log.removed", file.getFileName()));
            refreshModsAndResourcePacks();
            markPendingChanges();
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runToggleMod(ManagedFile file) {
        setModsControlsDisabled(true);
        try {
            filesAdminClient.toggleMod(file.getFileName());
            logModsSettings(Messages.get("mods.log.toggled", file.getFileName()));
            refreshModsAndResourcePacks();
            markPendingChanges();
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runSetModCategory(ManagedFile file, ModCategory category) {
        setModsControlsDisabled(true);
        try {
            filesAdminClient.setModCategory(file.getFileName(), category);
            logModsSettings(Messages.get("mods.log.categoryChanged", file.getFileName(),
                    Messages.get(switch (category) {
                        case SERVER_ONLY -> "value.category.serverOnly";
                        case CLIENT_ONLY -> "value.category.clientOnly";
                        case BOTH -> "value.category.both";
                    })));
            refreshModsAndResourcePacks();
            markPendingChanges();
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    /**
     * Bulk enable/disable — the window already filtered {@code files} down to just the checked
     * ones that actually need a flip to reach the requested state (see LauncherWindow.checkedNeedingFlip),
     * so every entry here gets exactly one toggleMod call. One failure doesn't stop the rest,
     * mirroring runAddMods's per-item try/catch.
     */
    private void runBulkToggleMods(List<ManagedFile> files) {
        if (files.isEmpty()) {
            return;
        }
        setModsControlsDisabled(true);
        try {
            for (ManagedFile file : files) {
                try {
                    filesAdminClient.toggleMod(file.getFileName());
                    logModsSettings(Messages.get("mods.log.toggled", file.getFileName()));
                } catch (Exception e) {
                    logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
                }
            }
            refreshModsAndResourcePacks();
            markPendingChanges();
        } finally {
            setModsControlsDisabled(false);
        }
    }

    /** One failed upload doesn't stop the rest — each file logs its own success/failure. */
    private void runAddResourcePacks(List<Path> files) {
        setModsControlsDisabled(true);
        try {
            for (Path file : files) {
                try {
                    filesAdminClient.uploadResourcePack(file);
                    logResourcePacks(Messages.get("mods.log.added", file.getFileName().toString()));
                } catch (Exception e) {
                    logResourcePacks(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
                }
            }
            refreshModsAndResourcePacks();
            markPendingChanges();
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runRemoveResourcePack(ManagedFile file) {
        setModsControlsDisabled(true);
        try {
            filesAdminClient.deleteResourcePack(file.getFileName());
            logResourcePacks(Messages.get("mods.log.removed", file.getFileName()));
            refreshModsAndResourcePacks();
            markPendingChanges();
        } catch (Exception e) {
            logResourcePacks(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runToggleResourcePack(ManagedFile file) {
        setModsControlsDisabled(true);
        try {
            filesAdminClient.toggleResourcePack(file.getFileName());
            logResourcePacks(Messages.get("mods.log.toggled", file.getFileName()));
            refreshModsAndResourcePacks();
            markPendingChanges();
        } catch (Exception e) {
            logResourcePacks(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    /** Bulk counterpart of runToggleResourcePack — see runBulkToggleMods for the shared filtering/error-handling rationale. */
    private void runBulkToggleResourcePacks(List<ManagedFile> files) {
        if (files.isEmpty()) {
            return;
        }
        setModsControlsDisabled(true);
        try {
            for (ManagedFile file : files) {
                try {
                    filesAdminClient.toggleResourcePack(file.getFileName());
                    logResourcePacks(Messages.get("mods.log.toggled", file.getFileName()));
                } catch (Exception e) {
                    logResourcePacks(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
                }
            }
            refreshModsAndResourcePacks();
            markPendingChanges();
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runLoadSettings() {
        setModsControlsDisabled(true);
        try {
            ServerSettings settings = managerClient.getSettings();
            Platform.runLater(() -> window.showSettings(settings));
            logModsSettings(Messages.get("settings.log.loaded"));
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runSaveSettings(ServerSettings settings) {
        setModsControlsDisabled(true);
        try {
            ServerSettings saved = managerClient.updateSettings(settings);
            Platform.runLater(() -> window.showSettings(saved));
            logModsSettings(Messages.get("settings.log.saved"));
            markPendingChanges();
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runLoadBackupSettings() {
        setManagerControlsDisabled(true);
        try {
            BackupSettings settings = managerClient.getBackupSettings();
            Platform.runLater(() -> window.showBackupSettings(settings));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    /** Takes effect immediately on server-manager's side (BackupScheduler.reconfigure) — no server restart needed. */
    private void runSaveBackupSettings(BackupSettings settings) {
        setManagerControlsDisabled(true);
        try {
            BackupSettings saved = managerClient.updateBackupSettings(settings);
            Platform.runLater(() -> window.showBackupSettings(saved));
            logManager(Messages.get("backupSettings.log.saved"));
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        } finally {
            setManagerControlsDisabled(false);
        }
    }

    private void runScheduleRestart(long delaySeconds) {
        setModsControlsDisabled(true);
        try {
            ManagerStatus status = managerClient.scheduleRestart(delaySeconds);
            Platform.runLater(() -> {
                window.showManagerStatus(status);
                window.setPendingChangesVisible(false);
            });
            logModsSettings(Messages.get("settings.log.restartScheduled", delaySeconds / 60));
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void runCancelScheduledRestart() {
        setModsControlsDisabled(true);
        try {
            ManagerStatus status = managerClient.cancelScheduledRestart();
            Platform.runLater(() -> window.showManagerStatus(status));
            logManager(Messages.get("settings.log.restartCancelled"));
        } catch (Exception e) {
            logManager(Messages.get("mods.log.failed", ErrorMessages.describe(e)));
        } finally {
            setModsControlsDisabled(false);
        }
    }

    private void markPendingChanges() {
        Platform.runLater(() -> window.setPendingChangesVisible(true));
    }

    private void setModsControlsDisabled(boolean disabled) {
        Platform.runLater(() -> window.setModsControlsDisabled(disabled));
    }

    private void onHostModeChange(boolean enabled) {
        config.setHostModeEnabled(enabled);
        if (enabled) {
            managerWorker.submit(this::launchHostedService);
        } else {
            managerWorker.submit(this::stopHostedServiceIfRunning);
        }
    }

    /**
     * mcsync.portforward.* is only read at server-manager startup (see
     * ServerManagerApp), so a toggle while hosting is already active needs
     * that one child process restarted to pick up the new setting —
     * sync-server is untouched since it doesn't care about this flag.
     */
    private void onPortForwardingChange(boolean enabled) {
        config.setPortForwardingEnabled(enabled);
        if (config.isHostModeEnabled()) {
            managerWorker.submit(this::restartHostedServerManagerForPortForwarding);
        }
    }

    private void restartHostedServerManagerForPortForwarding() {
        if (!LocalServiceLauncher.isSupported()) {
            return;
        }
        if (hostedServerManagerProcess != null && hostedServerManagerProcess.isAlive()) {
            hostedServerManagerProcess.destroy();
        }
        try {
            ServerProfile server = config.getActiveServer();
            String serverDirectory = config.getServerDirectory();
            hostedServerManagerProcess = localServiceLauncher.launchServerManager(serverDirectory, server.managerBaseUrl(), server.managerApiKey(),
                    config.isPortForwardingEnabled(), server.apiBaseUrl(), server.apiKey());
            logManager(Messages.get("manager.log.portForwardingRestarted"));
            awaitHostedServiceReady();
        } catch (Exception e) {
            logManager(Messages.get("manager.log.hostModeFailed", ErrorMessages.describe(e)));
        }
    }

    // The freshly spawned server-manager JVM needs a moment to bind its HTTP
    // port; awaitHostedServiceReady() polls quietly during this window
    // instead of refreshing immediately, so that window doesn't surface as a
    // misleading "operation failed: ConnectException" — see below.
    private static final Duration HOSTED_SERVICE_READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HOSTED_SERVICE_POLL_INTERVAL = Duration.ofMillis(500);

    /** Best-effort — logs failure rather than throwing, since this can run unattended on startup. */
    private void launchHostedService() {
        if (!LocalServiceLauncher.isSupported()) {
            logManager(Messages.get("manager.log.hostModeUnsupported"));
            return;
        }
        try {
            ServerProfile server = config.getActiveServer();
            String serverDirectory = config.getServerDirectory();
            if (hostedSyncServerProcess == null || !hostedSyncServerProcess.isAlive()) {
                hostedSyncServerProcess = localServiceLauncher.launchSyncServer(serverDirectory, server.apiBaseUrl(), server.apiKey());
            }
            if (hostedServerManagerProcess == null || !hostedServerManagerProcess.isAlive()) {
                hostedServerManagerProcess = localServiceLauncher.launchServerManager(serverDirectory, server.managerBaseUrl(), server.managerApiKey(),
                        config.isPortForwardingEnabled(), server.apiBaseUrl(), server.apiKey());
            }
            logManager(Messages.get("manager.log.hostModeStarted"));
            awaitHostedServiceReady();
        } catch (Exception e) {
            logManager(Messages.get("manager.log.hostModeFailed", ErrorMessages.describe(e)));
        }
    }

    /**
     * Polls both just-spawned instances until they answer or
     * {@link #HOSTED_SERVICE_READY_TIMEOUT} elapses, swallowing the expected
     * "still booting" connection failures along the way instead of logging
     * each one as a generic manager-action failure. Runs on managerWorker
     * (already a background thread), so the Thread.sleep below doesn't touch
     * the FX thread. Only a genuine timeout gets logged, as its own distinct
     * message so it doesn't get mistaken for the ordinary startup race.
     */
    private void awaitHostedServiceReady() {
        long deadlineNanos = System.nanoTime() + HOSTED_SERVICE_READY_TIMEOUT.toNanos();
        boolean managerReady = false;
        boolean syncReady = false;
        do {
            if (!managerReady) {
                try {
                    managerClient.getStatus();
                    managerReady = true;
                } catch (Exception e) {
                    // Still starting up -- expected, so no failure log here.
                }
            }
            if (!syncReady) {
                try {
                    filesAdminClient.listMods();
                    syncReady = true;
                } catch (Exception e) {
                    // Still starting up -- expected, so no failure log here.
                }
            }
            if (managerReady && syncReady) {
                refreshManagerStatus(); // now reachable -- pull full status + backups the normal way
                refreshModsAndResourcePacks();
                worker.submit(this::checkForUpdates); // sync-server's now up too -- recheck mod updates
                return;
            }
            try {
                Thread.sleep(HOSTED_SERVICE_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (System.nanoTime() < deadlineNanos);
        String timeoutMessage = Messages.get("manager.log.hostModeStartupTimeout", HOSTED_SERVICE_READY_TIMEOUT.toSeconds());
        logManager(timeoutMessage);
        managerStatus("manager.status.failed", timeoutMessage);
    }

    private boolean anyHostedServiceRunning() {
        return (hostedServerManagerProcess != null && hostedServerManagerProcess.isAlive())
                || (hostedSyncServerProcess != null && hostedSyncServerProcess.isAlive());
    }

    /** Unchecking the host-mode box while it's running — graceful stop, but the app itself keeps running. */
    private void stopHostedServiceIfRunning() {
        if (!anyHostedServiceRunning()) {
            return;
        }
        try {
            managerClient.stop();
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        }
        if (hostedServerManagerProcess != null) {
            hostedServerManagerProcess.destroy();
        }
        if (hostedSyncServerProcess != null) {
            hostedSyncServerProcess.destroy();
        }
        logManager(Messages.get("manager.log.hostModeStopped"));
    }

    /**
     * Runs on the FX thread (called synchronously from LauncherWindow's
     * stage.setOnCloseRequest). If host mode isn't hosting anything right
     * now, behaves exactly like the old unconditional Platform.exit() — the
     * dialog below only appears when there's actually something to lose.
     */
    private void handleCloseRequest() {
        if (!anyHostedServiceRunning()) {
            actuallyExit();
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, Messages.get("confirm.closeServerRunning.message"));
        alert.setTitle(Messages.get("confirm.closeServerRunning.title"));
        alert.setHeaderText(null);
        ButtonType minimize = new ButtonType(Messages.get("button.minimize"));
        ButtonType stopAndExit = new ButtonType(Messages.get("button.stopAndExit"));
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"));
        alert.getButtonTypes().setAll(minimize, stopAndExit, cancel);

        ButtonType choice = alert.showAndWait().orElse(cancel);
        if (choice == minimize) {
            window.hideToTaskbar();
        } else if (choice == stopAndExit) {
            managerWorker.submit(this::stopHostedServiceAndExit);
        }
        // cancel (or dialog dismissed) -> do nothing, window stays open
    }

    private void stopHostedServiceAndExit() {
        try {
            managerClient.stop();
        } catch (Exception e) {
            logManager(Messages.get("manager.log.failed", ErrorMessages.describe(e)));
        }
        if (hostedServerManagerProcess != null) {
            hostedServerManagerProcess.destroy();
        }
        if (hostedSyncServerProcess != null) {
            hostedSyncServerProcess.destroy();
        }
        actuallyExit();
    }

    private void actuallyExit() {
        worker.shutdownNow();
        managerWorker.shutdownNow();
        managerStatusPoller.shutdownNow();
        Platform.exit();
    }

    private void managerStatus(String key, Object... args) {
        Platform.runLater(() -> window.setManagerStatusRaw(Messages.get(key, args)));
    }

    private void setManagerControlsDisabled(boolean disabled) {
        Platform.runLater(() -> window.setManagerControlsDisabled(disabled));
    }

    private void log(String line) {
        Platform.runLater(() -> window.log(line));
    }

    private void logServer(String line) {
        Platform.runLater(() -> window.logServer(line));
    }

    private void logManager(String line) {
        Platform.runLater(() -> window.logManager(line));
    }

    private void logModsSettings(String line) {
        Platform.runLater(() -> window.logModsSettings(line));
    }

    private void logResourcePacks(String line) {
        Platform.runLater(() -> window.logResourcePacks(line));
    }

    private void status(String key, Object... args) {
        Platform.runLater(() -> window.setStatus(key, args));
    }

    private void showButtons(boolean update, boolean play) {
        Platform.runLater(() -> {
            window.setUpdateButtonVisible(update);
            window.setPlayButtonVisible(play);
        });
    }

    private void setControlsDisabled(boolean disabled) {
        Platform.runLater(() -> window.setControlsDisabled(disabled));
    }
}
