package dev.haraguro.modserverplaymanager.launcher.gui;

import dev.haraguro.modserverplaymanager.launcher.config.ConnectionFileCrypto;
import dev.haraguro.modserverplaymanager.launcher.config.LaunchProfile;
import dev.haraguro.modserverplaymanager.launcher.config.LauncherConfig;
import dev.haraguro.modserverplaymanager.launcher.config.ServerProfile;
import dev.haraguro.modserverplaymanager.launcher.i18n.Lang;
import dev.haraguro.modserverplaymanager.launcher.i18n.Messages;
import dev.haraguro.modserverplaymanager.launcher.launch.ErrorMessages;
import dev.haraguro.modserverplaymanager.launcher.update.AppVersion;
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
import dev.haraguro.modserverplaymanager.shared.model.WorldProfile;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The launcher's single window: a language selector (top right) above two
 * top-level tabs, each holding its own nested TabPane of sub-tabs —
 * **Client** (this PC's own connect-side concerns: **Play** — install dir,
 * active server at a glance, launch profile, a read-only mod/resource-pack
 * status view, status/progress, Update/Play/Close — and **Server** — the
 * editable connection form: API URL, game address, versions, API key with
 * a Generate button, saved-presets picker, Connect/Save Preset) and
 * **Host** (hosting/administering a server: **Server Management**
 * — start/stop/restart, live state/uptime/pid, host-mode toggle, manager
 * URL/key, connection-file export — talking to server-manager's HTTP API,
 * see ManagerClient; **Player** — online players, whitelist, recent
 * connection attempts; **Backup** — take a backup, browse past ones;
 * **Mod** — mod admin, split into three draggable-divider columns by
 * ModCategory — both/server-only/client-only, see ServerFilesService — so
 * it's obvious at a glance which mods actually go out to players;
 * **Resource Pack** — resource pack admin, no category split, resource
 * packs never run server-side; and **Settings** — curated
 * server.properties form). The Play tab's mod/resource-pack lists and the
 * Mod/Resource Pack tabs' editable lists share the same backing
 * {@code ObservableList} (modsItems/resourcePacksItems) — a single fetch in
 * showMods()/showResourcePacks() updates both. Most sub-tabs use the same
 * top/bottom split (see {@link #logSplit}): controls on top, that tab's
 * log below it, both filling the tab's full width with a player-draggable
 * divider between them, so the controls get most of the room by default
 * but either side can be resized to taste — Player and Backup are plain
 * single-pane tabs since they have no log stream of their own (their
 * actions still log to the Server Management tab's log). All mutator
 * methods must be called on the JavaFX application thread (i.e. from
 * inside Platform.runLater).
 */
public class LauncherWindow {

    private static final String STATUS_OK_STYLE = "-fx-text-fill: #2e7d32; -fx-font-weight: bold;";
    private static final String STATUS_WARN_STYLE = "-fx-text-fill: #c62828; -fx-font-weight: bold;";
    private static final String STATUS_NEUTRAL_STYLE = "-fx-text-fill: gray;";

    private final Label titleLabel = new Label();
    // Title bar at-a-glance status: mod/resourcepack sync state, whether
    // the active server is actually reachable+running, and a shortcut to
    // the same host-mode toggle the Server Manager tab exposes (kept in
    // sync with hostModeCheckBox — see show()). Deliberately terse (a
    // label + a checkbox, no full sentences) since they sit next to the
    // window title, not in a dedicated status panel.
    private final Label syncStatusIndicator = new Label();
    private final Label serverConnectableIndicator = new Label();
    private boolean updateButtonVisible = false;
    private boolean playButtonVisible = false;
    private boolean serverConnectable = false;
    private final Label languageLabel = new Label();
    private final ComboBox<Lang> languageBox = new ComboBox<>();

    // Quick-access profile row, directly below the title bar: which launch environment (memory +
    // player name, see LaunchProfile) and which server to connect to are always visible and
    // switchable without opening their tabs; the host-mode toggle (moved here from the title bar
    // itself) and, only while host mode is on, which profile's host settings are being managed.
    // Each *TitleBox mirrors its tab's own picker (same backing ObservableList, kept
    // selection-synced both ways) — same "shortcut control" pattern as hostModeTitleCheckBox used
    // to be on its own; see show() for the sync wiring.
    private final Label launchProfileTitleLabel = new Label();
    private final ComboBox<LaunchProfile> launchProfileTitleBox = new ComboBox<>();
    private final Label connectionProfileTitleLabel = new Label();
    private final ComboBox<ServerProfile> connectionProfileTitleBox = new ComboBox<>();
    private final CheckBox hostModeTitleCheckBox = new CheckBox();
    private final Label hostProfileTitleLabel = new Label();
    private final ComboBox<ServerProfile> hostProfileTitleBox = new ComboBox<>();
    private final HBox hostProfileTitleRow = new HBox(6);

    private final Label launcherUpdateLabel = new Label();
    private final Button downloadUpdateButton = new Button();
    private final HBox launcherUpdateRow = new HBox(10);
    // Manual counterpart to the automatic startup check above — sits next to
    // Reset since both are occasional app-maintenance actions, not daily use.
    private final Button checkForUpdateButton = new Button();

    private final Label installDirLabel = new Label();
    private final TextField installDirField = new TextField();
    private final Button browseButton = new Button();
    private final Button resetButton = new Button();
    // currentServerLabel shows just the profile name by default; the address is only appended
    // once toggleCurrentServerAddressButton reveals it — see refreshCurrentServerLabel(). Same
    // "don't leave connection endpoints on screen by default" reasoning as the Server tab's
    // maskable URL fields.
    private final Label currentServerLabel = new Label();
    private final Button toggleCurrentServerAddressButton = new Button();
    private boolean currentServerAddressVisible = false;
    private ServerProfile currentServerShown = ServerProfile.DEFAULT;
    // Manager URL/key of whichever profile populateServerFields() last saw — the Server tab no
    // longer has fields for these (see the field removal above), so serverFromFields() reads them
    // from here instead of blanking them out on every Connect/Save Preset/rename.
    private String currentManagerBaseUrl = ServerProfile.DEFAULT.managerBaseUrl();
    private String currentManagerApiKey = "";
    // Profiles imported from an encrypted connection settings file — see ConnectionFileCrypto and
    // applyMaskedOrRealText(). Synced from LauncherConfig's persisted set via setHiddenDetailProfileNames().
    private final Set<String> hiddenDetailProfileNames = new HashSet<>();

    private final Label serverPresetLabel = new Label();
    private final ComboBox<ServerProfile> serverBox = new ComboBox<>();
    // Delete/rename act on whichever profile is currently selected in serverBox — see
    // onRenameProfileClick/onDeleteProfileClick and their wiring in show().
    private final Button renameProfileButton = new Button();
    private final Button deleteProfileButton = new Button();
    private final Label apiUrlLabel = new Label();
    private final TextField apiUrlField = new TextField();
    // apiUrlField/gameAddressField are shown masked by default (like a password) with a Reveal
    // toggle — these are the actual connection endpoints for someone's server, not something to
    // leave visible on a shared screen. apiKeyField stays plain (see its own comment below); the
    // PasswordField half of each pair is purely cosmetic, kept text-synced via bindBidirectional —
    // see maskableField().
    private final PasswordField apiUrlMaskField = new PasswordField();
    private final Button apiUrlRevealButton = new Button();
    private final Label gameAddressLabel = new Label();
    private final TextField gameAddressField = new TextField();
    private final PasswordField gameAddressMaskField = new PasswordField();
    private final Button gameAddressRevealButton = new Button();
    // Always-available counterpart to showAddProfileDialog's fetch button — that one only helps
    // while adding a brand new profile; this lets an already-saved profile's address be re-fetched
    // any time the host updates their published address (see onFetchPublicGameAddress).
    private final Button fetchGameAddressButton = new Button();
    private final Label mcVersionLabel = new Label();
    private final TextField mcVersionField = new TextField();
    private final Label loaderVersionLabel = new Label();
    private final TextField loaderVersionField = new TextField();
    private final Label apiKeyLabel = new Label();
    // Plain (not masked) — this is a shared secret you hand to friends and
    // paste into a server startup command, not a personal login password;
    // being able to see/copy it matters more than hiding it.
    private final TextField apiKeyField = new TextField();
    // Manager URL/key are no longer edited here — connecting to a server never needs admin access
    // to server-manager, only the Server Manager tab's own *2 fields do (see serverFromFields(),
    // which now preserves whatever the selected profile already had instead of reading UI fields).
    private final Button connectButton = new Button();
    private final Button savePresetButton = new Button();
    // Explicit "start a brand new profile from scratch" affordance — see showAddProfileDialog().
    // savePresetButton alone technically supports this (type a new name, fill fields, Save) but
    // that's not obvious from the button's label; this makes it discoverable.
    private final Button addProfileButton = new Button();
    // Imports a connection settings file exported from someone else's Server Management tab (see
    // exportConnectionFileButton) — adds it as a saved profile, ready to Connect immediately.
    private final Button importProfileButton = new Button();
    // Plain-JSON export of this tab's own form (see serverFromFields) — same connect-only subset
    // as exportConnectionFileButton, just reachable without switching to the Server Manager tab.
    private final Button exportProfileButton = new Button();
    private final Label serverLogLabel = new Label();
    private final TextArea serverLogArea = new TextArea();
    private final Button serverLogCollapseButton = new Button();
    private final Button serverLogClearButton = new Button();

    private final Tab playTab = new Tab();
    private final Tab serverTab = new Tab();
    private final Tab serverManagerTab = new Tab();
    private final Tab playerTab = new Tab();
    private final Tab backupTab = new Tab();
    private final Tab worldsTab = new Tab();
    private final Tab modTab = new Tab();
    private final Tab resourcePacksTab = new Tab();
    private final Tab settingsTab = new Tab();
    // Top-level grouping: playTab/serverTab (this PC's own connect-side concerns) live under
    // clientGroupTab as sub-tabs; everything else (hosting/admin) lives under serverGroupTab —
    // see show()'s TabPane assembly.
    private final Tab clientGroupTab = new Tab();
    private final Tab serverGroupTab = new Tab();

    // Server Manager tab: basic connection settings + access-key generation, mirroring the
    // Server tab's fields (same underlying ServerProfile — see populateServerFields/
    // serverFromFields2) but with Generate buttons, which make sense here (configuring YOUR
    // OWN hosted server) and don't in the Server tab (pasting in a key someone else already
    // configured). Kept in sync with the Server tab's fields whenever the selected/active
    // server changes; "Save" writes back through the same onSaveHostSettingsClick path as
    // Connect/Save Preset. serverBox2 is this tab's own profile picker (shares serverBox's
    // backing list — see show()) so host settings for a given saved profile can be configured
    // here without switching to the Server tab first.
    private final Label hostProfileLabel = new Label();
    private final ComboBox<ServerProfile> serverBox2 = new ComboBox<>();
    private final Label hostSettingsLabel = new Label();
    private final Label apiUrlLabel2 = new Label();
    // Same maskable-by-default treatment as the Server tab's apiUrlField/gameAddressField/
    // managerUrlField2 below — see wireMaskable(); kept as the same helper so the reveal/hide
    // behavior is identical everywhere it appears, not a per-tab reimplementation.
    private final TextField apiUrlField2 = new TextField();
    private final PasswordField apiUrlMaskField2 = new PasswordField();
    private final Button apiUrlRevealButton2 = new Button();
    private final Label gameAddressLabel2 = new Label();
    private final TextField gameAddressField2 = new TextField();
    private final PasswordField gameAddressMaskField2 = new PasswordField();
    private final Button gameAddressRevealButton2 = new Button();
    private final Label mcVersionLabel2 = new Label();
    private final TextField mcVersionField2 = new TextField();
    private final Label loaderVersionLabel2 = new Label();
    private final TextField loaderVersionField2 = new TextField();
    private final Label apiKeyLabel2 = new Label();
    private final TextField apiKeyField2 = new TextField();
    private final Button generateApiKeyButton = new Button();
    private final Label managerUrlLabel2 = new Label();
    private final TextField managerUrlField2 = new TextField();
    private final PasswordField managerUrlMaskField2 = new PasswordField();
    private final Button managerUrlRevealButton2 = new Button();
    private final Label managerApiKeyLabel2 = new Label();
    private final TextField managerApiKeyField2 = new TextField();
    private final Button generateManagerApiKeyButton = new Button();
    private final Button saveHostSettingsButton = new Button();
    // Exports the active profile's connect-relevant fields (never the manager URL/key — those are
    // this PC's own admin access, not something to hand to a joining player) as a JSON file a
    // friend can import via the Server tab's importProfileButton to be ready to Connect immediately.
    private final Button exportConnectionFileButton = new Button();
    // Same export, password-encrypted (see ConnectionFileCrypto) — the importing side's Server tab
    // then keeps the sync URL/game address/API key out of the UI entirely (see hiddenDetailProfileNames).
    private final Button exportConnectionFileEncryptedButton = new Button();

    // Server Manager tab: host-mode toggle (auto-launch server-manager locally)
    private final CheckBox hostModeCheckBox = new CheckBox();
    private final Label serverDirectoryLabel = new Label();
    private final TextField serverDirectoryField = new TextField();
    private final Button serverDirectoryBrowseButton = new Button();
    private final HBox serverDirectoryRow = new HBox(10);

    // Server Manager tab: opt-in automatic UPnP port mapping (Minecraft +
    // sync-server ports) — only meaningful while host mode is on, see
    // portForwardingRow's visibility toggling in show().
    private final CheckBox portForwardingCheckBox = new CheckBox();
    private final Label portForwardingStatusLabel = new Label();
    private final Label portForwardingStatusValue = new Label();
    private final Button copyExternalIpButton = new Button();
    private final HBox portForwardingRow = new HBox(10);
    // Set by showPortForwardingStatus() whenever the router reports one — what
    // copyExternalIpToClipboard() actually copies; null (button hidden) until then.
    private String currentExternalIp;

    // Server Manager tab: live status (read-only)
    private final Label managerStateLabel = new Label();
    private final Label managerStateValue = new Label();
    private final Label managerUptimeLabel = new Label();
    private final Label managerUptimeValue = new Label();
    private final Label managerPidLabel = new Label();
    private final Label managerPidValue = new Label();
    private final Label managerLastBackupLabel = new Label();
    private final Label managerLastBackupValue = new Label();
    private final Label managerCrashCountLabel = new Label();
    private final Label managerCrashCountValue = new Label();
    private final Label backupsListLabel = new Label();
    private final ListView<BackupInfo> backupsListView = new ListView<>();
    private final Button managerRefreshButton = new Button();
    private final Button managerStartRestartButton = new Button();
    private final Button managerStopButton = new Button();
    private final Button managerBackupNowButton = new Button();
    private final Label managerStatusLabel = new Label();

    // Backup tab: policy settings (right column — see the Backup tab's layout comment below).
    // How many past backups to keep, which events trigger one, a shared cooldown for the
    // login/logout triggers (and any future participant-triggered one), and whether players
    // themselves are allowed to ask for a backup — see shared BackupSettings/server-manager's
    // BackupTriggerService for how these are actually enforced.
    private final Label backupSettingsLabel = new Label();
    private final Label retentionCountLabel = new Label();
    private final Spinner<Integer> retentionCountSpinner = new Spinner<>(1, 100, 10);
    private final CheckBox backupOnLoginCheckBox = new CheckBox();
    private final CheckBox backupOnLogoutCheckBox = new CheckBox();
    private final Label cooldownLabel = new Label();
    private final Spinner<Integer> cooldownSpinner = new Spinner<>(0, 86400, 300);
    private final CheckBox backupPeriodicCheckBox = new CheckBox();
    private final Label periodicIntervalLabel = new Label();
    private final Spinner<Integer> periodicIntervalSpinner = new Spinner<>(1, 1440, 60);
    private final CheckBox allowParticipantBackupCheckBox = new CheckBox();
    private final Button saveBackupSettingsButton = new Button();

    // Server Manager tab: online players + scheduled restart (both populated
    // by the same status refresh as State/Uptime/etc. above — no separate polling).
    private final Label onlinePlayersLabel = new Label();
    private final ListView<String> onlinePlayersListView = new ListView<>();
    private final Label scheduledRestartLabel = new Label();
    private final Label scheduledRestartValue = new Label();
    private final Button cancelScheduledRestartButton = new Button();
    private final Label managerLogLabel = new Label();
    private final TextArea managerLogArea = new TextArea();
    private final Button managerLogCollapseButton = new Button();
    private final Button managerLogClearButton = new Button();

    // Server Manager tab: whitelist admin (edits server/whitelist.json via
    // WhitelistService, reloaded live via RCON if the server is running).
    private final Label whitelistLabel = new Label();
    private final ListView<WhitelistEntry> whitelistListView = new ListView<>();
    private final TextField whitelistNameField = new TextField();
    private final Button addWhitelistButton = new Button();
    private final Button removeWhitelistButton = new Button();

    // Server Manager tab: recent join attempts (accepted and rejected —
    // see ConnectionLogParser) so the owner can grab a friend's exact
    // name/uuid without digging through the console log themselves.
    private final Label connectionAttemptsLabel = new Label();
    private final ListView<ConnectionAttempt> connectionAttemptsListView = new ListView<>();
    private final Button addAttemptToWhitelistButton = new Button();

    // Worlds tab: switch/create/rename/delete server/<name>/ world saves — see
    // server-manager's WorldProfileService. Activate stops/switches/restarts the
    // server automatically if it was running; create/rename/delete require it stopped.
    private final Label worldsLabel = new Label();
    private final ListView<WorldProfile> worldsListView = new ListView<>();
    private final TextField newWorldNameField = new TextField();
    private final CheckBox cloneActiveWorldCheckBox = new CheckBox();
    private final Button createWorldButton = new Button();
    private final Button activateWorldButton = new Button();
    private final Button renameWorldButton = new Button();
    private final Button deleteWorldButton = new Button();

    // Mods & Settings tab: sync-server's own admin toggle (as opposed to the curated
    // server.properties form further down) — off by default, matching the original
    // "uploads always overwrite" behavior; see SyncServerSettings.
    // Shared between the Mod tab and Resource Pack tab (both upload to sync-server, and
    // SyncServerSettings.preventOverwriteOnUpload is a single server-wide toggle) — two
    // CheckBox instances kept in sync, see showSyncSettings().
    private final CheckBox preventOverwriteCheckBox = new CheckBox();
    private final CheckBox resourcePacksPreventOverwriteCheckBox = new CheckBox();

    // Mod tab: host-entered public "host:port" that clients can fetch (via SyncServerSettings,
    // same API-key-gated endpoint) instead of being told it out of band — see showAddProfileDialog's
    // fetch button, the other half of this feature.
    private final Label publicGameAddressLabel = new Label();
    private final TextField publicGameAddressField = new TextField();
    private final Button savePublicGameAddressButton = new Button();

    // Mod tab: server-side mod admin (add/remove/enable-disable — the reverse direction of
    // the Play tab's sync-and-download), split into three lists by ModCategory so it's obvious
    // at a glance which mods actually go out to players — see ServerFilesService.buildManifest.
    private final Label modsBothLabel = new Label();
    private final ListView<ManagedFile> modsBothListView = new ListView<>();
    private final Label modsServerOnlyLabel = new Label();
    private final ListView<ManagedFile> modsServerOnlyListView = new ListView<>();
    private final Label modsClientOnlyLabel = new Label();
    private final ListView<ManagedFile> modsClientOnlyListView = new ListView<>();
    private final Button addModButton = new Button();
    private final Button removeModButton = new Button();
    private final Button toggleModButton = new Button();
    private final Button openModsFolderButton = new Button();
    // Reclassifies whichever mod is currently selected across the three lists above (see
    // withSelectedMod) — moving it into a different list on the next refresh.
    private final Button moveModToBothButton = new Button();
    private final Button moveModToServerOnlyButton = new Button();
    private final Button moveModToClientOnlyButton = new Button();
    // Per-row checkboxes (see editableManagedFileCell) mark files for the bulk
    // enable/disable buttons below — independent of the three ListViews' own single-item
    // selection (see withSelectedMod), which still drives Remove/Enable-Disable/move-to-category.
    // Keyed by file name so checked state survives a modsItems refresh (a new ManagedFile
    // instance with the same name looks "still checked"), and shared across all three category
    // lists since a file name only ever appears in one of them at a time.
    private final Set<String> checkedModNames = new HashSet<>();
    private final Button selectAllModsButton = new Button();
    private final Button selectNoneModsButton = new Button();
    private final Button enableSelectedModsButton = new Button();
    private final Button disableSelectedModsButton = new Button();

    // Resource Pack tab: same admin shape as the Mod tab, minus the category split — resource
    // packs never run server-side, so ModCategory doesn't apply to them.
    private final Label resourcePacksListLabel = new Label();
    private final ListView<ManagedFile> resourcePacksListView = new ListView<>();
    private final Button addResourcePackButton = new Button();
    private final Button removeResourcePackButton = new Button();
    private final Button toggleResourcePackButton = new Button();
    private final Button openResourcePacksFolderButton = new Button();
    private final Set<String> checkedResourcePackNames = new HashSet<>();
    private final Button selectAllResourcePacksButton = new Button();
    private final Button selectNoneResourcePacksButton = new Button();
    private final Button enableSelectedResourcePacksButton = new Button();
    private final Button disableSelectedResourcePacksButton = new Button();
    private final Label resourcePacksLogLabel = new Label();
    private final TextArea resourcePacksLogArea = new TextArea();
    private final Button resourcePacksLogCollapseButton = new Button();
    private final Button resourcePacksLogClearButton = new Button();

    // Mods & Settings tab: curated server.properties form
    private final Label settingsLabel = new Label();
    private final Label difficultyLabel = new Label();
    private final ComboBox<String> difficultyBox = new ComboBox<>();
    private final Label gamemodeLabel = new Label();
    private final ComboBox<String> gamemodeBox = new ComboBox<>();
    private final Label motdLabel = new Label();
    private final TextField motdField = new TextField();
    private final Label maxPlayersLabel = new Label();
    private final Spinner<Integer> maxPlayersSpinner = new Spinner<>(1, 1000, 20);
    private final Label viewDistanceLabel = new Label();
    private final Spinner<Integer> viewDistanceSpinner = new Spinner<>(2, 32, 10);
    private final Label simulationDistanceLabel = new Label();
    private final Spinner<Integer> simulationDistanceSpinner = new Spinner<>(2, 32, 10);
    private final Label spawnProtectionLabel = new Label();
    private final Spinner<Integer> spawnProtectionSpinner = new Spinner<>(0, 1000, 16);
    private final CheckBox whitelistCheckBox = new CheckBox();
    private final CheckBox hardcoreCheckBox = new CheckBox();
    private final CheckBox allowFlightCheckBox = new CheckBox();
    private final Button loadSettingsButton = new Button();
    private final Button saveSettingsButton = new Button();
    private final Label settingsNoteLabel = new Label();

    // Mods & Settings tab: pending-changes banner (same visual pattern as launcherUpdateRow)
    private final Label pendingChangesLabel = new Label();
    private final ComboBox<Integer> restartDelayMinutesBox = new ComboBox<>();
    private final Button restartNowButton = new Button();
    private final Button scheduleRestartButton = new Button();
    private final Button dismissPendingChangesButton = new Button();
    private final HBox pendingChangesRow = new HBox(10);
    private final Label modsSettingsLogLabel = new Label();
    private final TextArea modsSettingsLogArea = new TextArea();
    private final Button modsSettingsLogCollapseButton = new Button();
    private final Button modsSettingsLogClearButton = new Button();

    private final Label launchProfileLabel = new Label();
    private final ComboBox<LaunchProfile> launchProfileBox = new ComboBox<>();
    // Full profile management, same 5 operations and wording/order as the Server tab's profile
    // row (see serverPresetRow) — 新規作成/名前変更/読み込み/書き出し/削除 (add/rename/import/export/delete).
    private final Button addLaunchProfileButton = new Button();
    private final Button renameLaunchProfileButton = new Button();
    private final Button importLaunchProfileButton = new Button();
    private final Button exportLaunchProfileButton = new Button();
    private final Button deleteLaunchProfileButton = new Button();

    // Play tab: account section — offline player name (default) or a signed-in
    // Microsoft account. isMsaSignedIn() drives which half is shown; see
    // updateAccountLabel(). The device-code row is a hidden-until-needed
    // banner (same visibility pattern as launcherUpdateRow) shown for the
    // duration of an interactive sign-in.
    private final Label accountHeaderLabel = new Label();
    private final Label accountStatusLabel = new Label();
    private final Label playerNameLabel = new Label();
    private final TextField playerNameField = new TextField();
    private final Button changeSkinButton = new Button();
    private final Label msaClientIdLabel = new Label();
    private final TextField msaClientIdField = new TextField();
    private final Button signInButton = new Button();
    private final Button signOutButton = new Button();
    private final Label msaDeviceCodeLabel = new Label();
    private final Button msaOpenBrowserButton = new Button();
    private final Button msaCancelButton = new Button();
    private final HBox msaDeviceCodeRow = new HBox(10);
    private boolean accountSignedIn = false;
    private String accountOfflineName = "";
    private String accountMsaUsername = "";

    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TextArea logArea = new TextArea();
    private final Label playLogLabel = new Label();
    private final Button playLogCollapseButton = new Button();
    private final Button playLogClearButton = new Button();

    // Play tab: read-only mod/resource-pack status, sharing the exact same
    // ObservableList backing (see modsItems/resourcePacksItems below) as the
    // Mods & Settings tab's editable lists — one fetch, two views. Shown as
    // two side-by-side columns (see show()) rather than stacked, each with
    // its own Copy List button.
    private final Label playModsLabel = new Label();
    private final ListView<ManagedFile> playModsListView = new ListView<>();
    private final Button copyModsListButton = new Button();
    private final Label playResourcePacksLabel = new Label();
    private final ListView<ManagedFile> playResourcePacksListView = new ListView<>();
    private final Button copyResourcePacksListButton = new Button();
    private final ObservableList<ManagedFile> modsItems = FXCollections.observableArrayList();
    private final ObservableList<ManagedFile> resourcePacksItems = FXCollections.observableArrayList();

    private final Button updateButton = new Button();
    private final Button playButton = new Button();
    private final Button closeButton = new Button();

    private Stage stage;
    private String statusKey = "status.starting";
    private Object[] statusArgs = new Object[0];
    private String pendingUpdateVersion;
    private String pendingUpdateAssetUrl;

    private Consumer<Lang> onLanguageChange = lang -> {
    };
    private Consumer<Path> onInstallDirChange = dir -> {
    };
    private Runnable onResetClick = () -> {
    };
    private Consumer<ServerProfile> onConnectClick = server -> {
    };
    /**
     * Fires whenever the profile picker's *selection* changes (serverBox or serverBox2) — not just
     * on Connect. Player/Backup/Mod/Resource Pack/Settings tabs all show data pulled live from
     * whichever server this points the admin clients at (see LauncherApp), so simply picking a
     * different saved profile is enough to bind those tabs to it; the active server used for actual
     * Play/launch only changes on an explicit Connect (onConnectClick) or Save (onSaveHostSettingsClick).
     */
    private Consumer<ServerProfile> onViewProfileChange = server -> {
    };
    private Consumer<ServerProfile> onSavePresetClick = server -> {
    };
    /** @param handler receives (oldName, renamedProfile) — see LauncherConfig.renameServerPreset. */
    private BiConsumer<String, ServerProfile> onRenameProfileClick = (oldName, renamed) -> {
    };
    private Consumer<ServerProfile> onDeleteProfileClick = server -> {
    };
    /** Server Management tab's "Save" — writes the *2 fields back as the active server, same as Connect. */
    private Consumer<ServerProfile> onSaveHostSettingsClick = server -> {
    };
    /** @param handler receives (profile, destination) — writes the connect-only subset as JSON. */
    private BiConsumer<ServerProfile, Path> onExportConnectionFileClick = (server, path) -> {
    };
    /** Password-encrypted counterpart of onExportConnectionFileClick — see ConnectionFileCrypto. */
    private Consumer<ExportEncryptedRequest> onExportConnectionFileEncryptedClick = request -> {
    };
    private Consumer<Path> onImportConnectionFileClick = path -> {
    };
    /** @param handler receives (source, password) for a file ConnectionFileCrypto.looksEncrypted() flagged. */
    private BiConsumer<Path, String> onImportEncryptedConnectionFileClick = (path, password) -> {
    };
    private Consumer<LaunchProfile> onLaunchProfileChange = profile -> {
    };
    /** Adds/replaces (by name) and selects — see LauncherConfig.saveLaunchProfile. */
    private Consumer<LaunchProfile> onSaveLaunchProfileClick = profile -> {
    };
    /** @param handler receives (oldName, renamedProfile) — see LauncherConfig.renameLaunchProfile. */
    private BiConsumer<String, LaunchProfile> onRenameLaunchProfileClick = (oldName, renamed) -> {
    };
    private Consumer<LaunchProfile> onDeleteLaunchProfileClick = profile -> {
    };
    /** @param handler receives (profile, destination) — writes the whole profile as JSON (nothing sensitive to strip, unlike ServerProfile). */
    private BiConsumer<LaunchProfile, Path> onExportLaunchProfileClick = (profile, path) -> {
    };
    private Consumer<Path> onImportLaunchProfileClick = path -> {
    };
    private Runnable onUpdateClick = () -> {
    };
    private Runnable onPlayClick = () -> {
    };
    private Consumer<String> onUpdateNowClick = assetUrl -> {
    };
    private Runnable onCheckForUpdateClick = () -> {
    };
    private Runnable onManagerRefreshClick = () -> {
    };
    private Runnable onManagerStartRestartClick = () -> {
    };
    private Runnable onManagerStopClick = () -> {
    };
    private Runnable onManagerBackupNowClick = () -> {
    };
    private Consumer<Boolean> onHostModeChange = enabled -> {
    };
    private Consumer<Boolean> onPortForwardingChange = enabled -> {
    };
    private Consumer<String> onServerDirectoryChange = dir -> {
    };
    private Runnable onCloseRequest = () -> {
    };
    private Consumer<List<Path>> onAddModClick = files -> {
    };
    private Consumer<Path> onChangeSkinClick = file -> {
    };
    private Consumer<ManagedFile> onRemoveModClick = file -> {
    };
    private Consumer<ManagedFile> onToggleModClick = file -> {
    };
    private Consumer<List<ManagedFile>> onBulkToggleModsClick = files -> {
    };
    private BiConsumer<ManagedFile, ModCategory> onSetModCategoryClick = (file, category) -> {
    };
    private Consumer<Boolean> onPreventOverwriteChange = enabled -> {
    };
    private Consumer<String> onSavePublicGameAddressClick = address -> {
    };
    // Add-profile dialog's "fetch" button: (apiUrl, apiKey) -> the host's published address, or
    // a failed/null future if unset or unreachable. Not tied to the active profile's own client
    // since this fetches for a server the user hasn't added yet — see LauncherApp's wiring.
    // Contract: the returned future is always completed on the FX thread (LauncherApp wraps the
    // background HTTP call's completion in Platform.runLater), so callers here can update controls
    // directly from a whenComplete/thenAccept without their own runLater.
    private BiFunction<String, String, CompletableFuture<String>> onFetchPublicGameAddress =
            (apiUrl, apiKey) -> CompletableFuture.completedFuture(null);
    private Consumer<List<Path>> onAddResourcePackClick = files -> {
    };
    private Consumer<ManagedFile> onRemoveResourcePackClick = file -> {
    };
    private Consumer<ManagedFile> onToggleResourcePackClick = file -> {
    };
    private Consumer<List<ManagedFile>> onBulkToggleResourcePacksClick = files -> {
    };
    private Runnable onLoadSettingsClick = () -> {
    };
    private Consumer<ServerSettings> onSaveSettingsClick = settings -> {
    };
    private Consumer<BackupSettings> onSaveBackupSettingsClick = settings -> {
    };
    private Consumer<Long> onScheduleRestartClick = delaySeconds -> {
    };
    private Runnable onCancelScheduledRestartClick = () -> {
    };
    private Consumer<String> onAddWhitelistClick = name -> {
    };
    private Consumer<String> onRemoveWhitelistClick = name -> {
    };
    private BiConsumer<String, Boolean> onCreateWorldClick = (name, cloneActive) -> {
    };
    private Consumer<String> onActivateWorldClick = name -> {
    };
    private BiConsumer<String, String> onRenameWorldClick = (name, newName) -> {
    };
    private Consumer<String> onDeleteWorldClick = name -> {
    };
    private Consumer<String> onPlayerNameChange = name -> {
    };
    private Consumer<String> onMsaClientIdChange = clientId -> {
    };
    private Runnable onSignInClick = () -> {
    };
    private Runnable onSignOutClick = () -> {
    };
    private Runnable onCancelSignInClick = () -> {
    };

    public void setOnLanguageChange(Consumer<Lang> handler) {
        this.onLanguageChange = handler;
    }

    public void setOnInstallDirChange(Consumer<Path> handler) {
        this.onInstallDirChange = handler;
    }

    public void setOnResetClick(Runnable handler) {
        this.onResetClick = handler;
    }

    public void setOnConnectClick(Consumer<ServerProfile> handler) {
        this.onConnectClick = handler;
    }

    public void setOnViewProfileChange(Consumer<ServerProfile> handler) {
        this.onViewProfileChange = handler;
    }

    public void setOnSavePresetClick(Consumer<ServerProfile> handler) {
        this.onSavePresetClick = handler;
    }

    public void setOnRenameProfileClick(BiConsumer<String, ServerProfile> handler) {
        this.onRenameProfileClick = handler;
    }

    public void setOnDeleteProfileClick(Consumer<ServerProfile> handler) {
        this.onDeleteProfileClick = handler;
    }

    public void setOnSaveHostSettingsClick(Consumer<ServerProfile> handler) {
        this.onSaveHostSettingsClick = handler;
    }

    public void setOnExportConnectionFileClick(BiConsumer<ServerProfile, Path> handler) {
        this.onExportConnectionFileClick = handler;
    }

    /** Payload for {@link #setOnExportConnectionFileEncryptedClick} — three values, so a named type beats a raw tri-consumer. */
    public record ExportEncryptedRequest(ServerProfile server, String password, Path destination) {
    }

    public void setOnExportConnectionFileEncryptedClick(Consumer<ExportEncryptedRequest> handler) {
        this.onExportConnectionFileEncryptedClick = handler;
    }

    public void setOnImportConnectionFileClick(Consumer<Path> handler) {
        this.onImportConnectionFileClick = handler;
    }

    public void setOnImportEncryptedConnectionFileClick(BiConsumer<Path, String> handler) {
        this.onImportEncryptedConnectionFileClick = handler;
    }

    public void setOnLaunchProfileChange(Consumer<LaunchProfile> handler) {
        this.onLaunchProfileChange = handler;
    }

    public void setOnSaveLaunchProfileClick(Consumer<LaunchProfile> handler) {
        this.onSaveLaunchProfileClick = handler;
    }

    public void setOnRenameLaunchProfileClick(BiConsumer<String, LaunchProfile> handler) {
        this.onRenameLaunchProfileClick = handler;
    }

    public void setOnDeleteLaunchProfileClick(Consumer<LaunchProfile> handler) {
        this.onDeleteLaunchProfileClick = handler;
    }

    public void setOnExportLaunchProfileClick(BiConsumer<LaunchProfile, Path> handler) {
        this.onExportLaunchProfileClick = handler;
    }

    public void setOnImportLaunchProfileClick(Consumer<Path> handler) {
        this.onImportLaunchProfileClick = handler;
    }

    public void setOnUpdateClick(Runnable handler) {
        this.onUpdateClick = handler;
    }

    public void setOnPlayClick(Runnable handler) {
        this.onPlayClick = handler;
    }

    public void setOnUpdateNowClick(Consumer<String> handler) {
        this.onUpdateNowClick = handler;
    }

    public void setOnCheckForUpdateClick(Runnable handler) {
        this.onCheckForUpdateClick = handler;
    }

    public void setOnManagerRefreshClick(Runnable handler) {
        this.onManagerRefreshClick = handler;
    }

    public void setOnManagerStartRestartClick(Runnable handler) {
        this.onManagerStartRestartClick = handler;
    }

    public void setOnManagerStopClick(Runnable handler) {
        this.onManagerStopClick = handler;
    }

    public void setOnManagerBackupNowClick(Runnable handler) {
        this.onManagerBackupNowClick = handler;
    }

    public void setOnHostModeChange(Consumer<Boolean> handler) {
        this.onHostModeChange = handler;
    }

    public void setOnPortForwardingChange(Consumer<Boolean> handler) {
        this.onPortForwardingChange = handler;
    }

    public void setOnServerDirectoryChange(Consumer<String> handler) {
        this.onServerDirectoryChange = handler;
    }

    /** Called for both the window's close button and its title-bar X — see show()'s stage.setOnCloseRequest. */
    public void setOnCloseRequest(Runnable handler) {
        this.onCloseRequest = handler;
    }

    /** @param handler receives every file picked in one go — the FileChooser allows multi-select. */
    public void setOnAddModClick(Consumer<List<Path>> handler) {
        this.onAddModClick = handler;
    }

    public void setOnChangeSkinClick(Consumer<Path> handler) {
        this.onChangeSkinClick = handler;
    }

    public void setOnRemoveModClick(Consumer<ManagedFile> handler) {
        this.onRemoveModClick = handler;
    }

    public void setOnToggleModClick(Consumer<ManagedFile> handler) {
        this.onToggleModClick = handler;
    }

    /** @param handler receives only the checked mods that actually need a flip to reach the requested state. */
    public void setOnBulkToggleModsClick(Consumer<List<ManagedFile>> handler) {
        this.onBulkToggleModsClick = handler;
    }

    /** @param handler receives the mod and the category it should be reclassified as. */
    public void setOnSetModCategoryClick(BiConsumer<ManagedFile, ModCategory> handler) {
        this.onSetModCategoryClick = handler;
    }

    public void setOnPreventOverwriteChange(Consumer<Boolean> handler) {
        this.onPreventOverwriteChange = handler;
    }

    public void setOnSavePublicGameAddressClick(Consumer<String> handler) {
        this.onSavePublicGameAddressClick = handler;
    }

    public void setOnFetchPublicGameAddress(BiFunction<String, String, CompletableFuture<String>> handler) {
        this.onFetchPublicGameAddress = handler;
    }

    /** @param handler receives every file picked in one go — the FileChooser allows multi-select. */
    public void setOnAddResourcePackClick(Consumer<List<Path>> handler) {
        this.onAddResourcePackClick = handler;
    }

    public void setOnRemoveResourcePackClick(Consumer<ManagedFile> handler) {
        this.onRemoveResourcePackClick = handler;
    }

    public void setOnToggleResourcePackClick(Consumer<ManagedFile> handler) {
        this.onToggleResourcePackClick = handler;
    }

    /** @param handler receives only the checked resource packs that actually need a flip to reach the requested state. */
    public void setOnBulkToggleResourcePacksClick(Consumer<List<ManagedFile>> handler) {
        this.onBulkToggleResourcePacksClick = handler;
    }

    public void setOnAddWhitelistClick(Consumer<String> handler) {
        this.onAddWhitelistClick = handler;
    }

    public void setOnRemoveWhitelistClick(Consumer<String> handler) {
        this.onRemoveWhitelistClick = handler;
    }

    public void setOnCreateWorldClick(BiConsumer<String, Boolean> handler) {
        this.onCreateWorldClick = handler;
    }

    public void setOnActivateWorldClick(Consumer<String> handler) {
        this.onActivateWorldClick = handler;
    }

    public void setOnRenameWorldClick(BiConsumer<String, String> handler) {
        this.onRenameWorldClick = handler;
    }

    public void setOnDeleteWorldClick(Consumer<String> handler) {
        this.onDeleteWorldClick = handler;
    }

    public void setOnLoadSettingsClick(Runnable handler) {
        this.onLoadSettingsClick = handler;
    }

    public void setOnSaveSettingsClick(Consumer<ServerSettings> handler) {
        this.onSaveSettingsClick = handler;
    }

    public void setOnSaveBackupSettingsClick(Consumer<BackupSettings> handler) {
        this.onSaveBackupSettingsClick = handler;
    }

    /** @param handler receives the requested delay in seconds */
    public void setOnScheduleRestartClick(Consumer<Long> handler) {
        this.onScheduleRestartClick = handler;
    }

    public void setOnCancelScheduledRestartClick(Runnable handler) {
        this.onCancelScheduledRestartClick = handler;
    }

    public void setOnPlayerNameChange(Consumer<String> handler) {
        this.onPlayerNameChange = handler;
    }

    public void setOnMsaClientIdChange(Consumer<String> handler) {
        this.onMsaClientIdChange = handler;
    }

    public void setOnSignInClick(Runnable handler) {
        this.onSignInClick = handler;
    }

    public void setOnSignOutClick(Runnable handler) {
        this.onSignOutClick = handler;
    }

    public void setOnCancelSignInClick(Runnable handler) {
        this.onCancelSignInClick = handler;
    }

    public void show(Stage stage, LauncherConfig config) {
        this.stage = stage;

        languageBox.getItems().addAll(Lang.values());
        languageBox.setConverter(nameConverter(Lang::displayName));
        languageBox.setValue(Messages.currentLang());
        languageBox.setOnAction(e -> {
            Lang selected = languageBox.getValue();
            if (selected != null) {
                onLanguageChange.accept(selected);
            }
        });

        installDirField.setText(config.getInstallDir().toString());
        installDirField.setOnAction(e -> onInstallDirChange.accept(Path.of(installDirField.getText())));
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File current = new File(installDirField.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File chosen = chooser.showDialog(stage);
            if (chosen != null) {
                installDirField.setText(chosen.getAbsolutePath());
                onInstallDirChange.accept(chosen.toPath());
            }
        });
        resetButton.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    Messages.get("confirm.reset.message"), ButtonType.YES, ButtonType.NO);
            confirm.setTitle(Messages.get("confirm.reset.title"));
            confirm.setHeaderText(null);
            confirm.showAndWait().filter(response -> response == ButtonType.YES)
                    .ifPresent(response -> onResetClick.run());
        });

        serverBox.setEditable(true);
        serverBox.getItems().addAll(config.getServers());
        serverBox.setConverter(nameConverter(ServerProfile::name));
        serverBox.setOnAction(e -> {
            ServerProfile selected = serverBox.getValue();
            if (selected != null) {
                selectServerProfileEverywhere(selected);
            }
        });
        // Server Management tab's own profile picker — same backing list (see below) as serverBox,
        // kept selection-synced with it in both directions (and with the title-bar shortcuts — see
        // selectServerProfileEverywhere) so host settings for a given profile can be reached from
        // any of them.
        serverBox2.setItems(serverBox.getItems());
        serverBox2.setConverter(nameConverter(ServerProfile::name));
        serverBox2.setOnAction(e -> {
            ServerProfile selected = serverBox2.getValue();
            if (selected != null) {
                selectServerProfileEverywhere(selected);
            }
        });
        populateServerFields(config.getActiveServer());
        serverBox.setValue(config.getActiveServer());
        serverBox2.setValue(config.getActiveServer());
        connectionProfileTitleBox.setValue(config.getActiveServer());
        hostProfileTitleBox.setValue(config.getActiveServer());

        connectButton.setOnAction(e -> onConnectClick.accept(serverFromFields()));
        savePresetButton.setOnAction(e -> {
            ServerProfile server = serverFromFields();
            onSavePresetClick.accept(server);
            if (!serverBox.getItems().contains(server)) {
                serverBox.getItems().removeIf(s -> s.name().equals(server.name()));
                serverBox.getItems().add(server);
            }
        });
        addProfileButton.setOnAction(e -> showAddProfileDialog());
        // serverBox is editable — typing a new name into it then clicking Rename is the whole flow,
        // no separate dialog needed. Any other field edits made before clicking come along too
        // (serverFromFields() reads the live form), same as a rename-while-editing.
        renameProfileButton.setOnAction(e -> {
            ServerProfile selected = serverBox.getValue();
            String newName = serverBox.getEditor().getText() == null ? "" : serverBox.getEditor().getText().trim();
            if (selected == null || newName.isEmpty() || newName.equals(selected.name())) {
                return;
            }
            onRenameProfileClick.accept(selected.name(), serverFromFields());
        });
        deleteProfileButton.setOnAction(e -> {
            ServerProfile selected = serverBox.getValue();
            if (selected == null) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    Messages.get("confirm.deleteProfile.message", selected.name()), ButtonType.YES, ButtonType.NO);
            confirm.setTitle(Messages.get("confirm.deleteProfile.title"));
            confirm.setHeaderText(null);
            confirm.showAndWait().filter(response -> response == ButtonType.YES)
                    .ifPresent(response -> onDeleteProfileClick.accept(selected));
        });
        importProfileButton.setOnAction(e -> {
            Path file = chooseFile(stage, "*.json");
            if (file == null) {
                return;
            }
            String content;
            try {
                content = Files.readString(file);
            } catch (Exception ex) {
                showSimpleAlert(Alert.AlertType.ERROR, "alert.importConnectionFile.error.title",
                        Messages.get("alert.importConnectionFile.error.content", ErrorMessages.describe(ex)));
                return;
            }
            if (ConnectionFileCrypto.looksEncrypted(content)) {
                showPasswordPromptDialog("dialog.connectionFilePassword.import.title").ifPresent(password ->
                        onImportEncryptedConnectionFileClick.accept(file, password));
            } else {
                onImportConnectionFileClick.accept(file);
            }
        });
        exportProfileButton.setOnAction(e -> {
            ServerProfile server = serverFromFields();
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName(server.name() + "-connect.json");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
            File file = chooser.showSaveDialog(stage);
            if (file != null) {
                onExportConnectionFileClick.accept(server, file.toPath());
            }
        });

        wireMaskable(apiUrlField, apiUrlMaskField, apiUrlRevealButton);
        wireMaskable(gameAddressField, gameAddressMaskField, gameAddressRevealButton);
        wireMaskable(apiUrlField2, apiUrlMaskField2, apiUrlRevealButton2);
        wireMaskable(gameAddressField2, gameAddressMaskField2, gameAddressRevealButton2);
        wireMaskable(managerUrlField2, managerUrlMaskField2, managerUrlRevealButton2);
        fetchGameAddressButton.setOnAction(e -> {
            fetchGameAddressButton.setDisable(true);
            onFetchPublicGameAddress.apply(apiUrlField.getText().trim(), apiKeyField.getText().trim())
                    .whenComplete((address, error) -> {
                        fetchGameAddressButton.setDisable(false);
                        if (error != null) {
                            Alert alert = new Alert(Alert.AlertType.ERROR,
                                    Messages.get("alert.fetchGameAddress.error.content", ErrorMessages.describe(error)),
                                    ButtonType.OK);
                            alert.setTitle(Messages.get("alert.fetchGameAddress.error.title"));
                            alert.setHeaderText(null);
                            alert.showAndWait();
                        } else if (address == null || address.isBlank()) {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                                    Messages.get("alert.fetchGameAddress.notPublished.content"), ButtonType.OK);
                            alert.setTitle(Messages.get("alert.fetchGameAddress.notPublished.title"));
                            alert.setHeaderText(null);
                            alert.showAndWait();
                        } else {
                            gameAddressField.setText(address);
                        }
                    });
        });

        generateApiKeyButton.setOnAction(e -> {
            apiKeyField2.setText(generateApiKey());
            onSaveHostSettingsClick.accept(serverFromFields2()); // record the new key immediately, not just on the next explicit Save
        });
        generateManagerApiKeyButton.setOnAction(e -> {
            managerApiKeyField2.setText(generateApiKey());
            onSaveHostSettingsClick.accept(serverFromFields2());
        });
        saveHostSettingsButton.setOnAction(e -> onSaveHostSettingsClick.accept(serverFromFields2()));
        exportConnectionFileButton.setOnAction(e -> {
            ServerProfile server = serverFromFields2();
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName(server.name() + "-connect.json");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
            File file = chooser.showSaveDialog(stage);
            if (file != null) {
                onExportConnectionFileClick.accept(server, file.toPath());
            }
        });
        exportConnectionFileEncryptedButton.setOnAction(e -> {
            ServerProfile server = serverFromFields2();
            showPasswordPromptDialog("dialog.connectionFilePassword.export.title").ifPresent(password -> {
                FileChooser chooser = new FileChooser();
                chooser.setInitialFileName(server.name() + "-connect.encrypted.json");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
                File file = chooser.showSaveDialog(stage);
                if (file != null) {
                    onExportConnectionFileEncryptedClick.accept(new ExportEncryptedRequest(server, password, file.toPath()));
                }
            });
        });

        launchProfileBox.getItems().addAll(config.getLaunchProfiles());
        launchProfileBox.setConverter(nameConverter(LaunchProfile::name));
        launchProfileBox.setValue(config.getSelectedLaunchProfile());
        launchProfileBox.setOnAction(e -> {
            LaunchProfile selected = launchProfileBox.getValue();
            if (selected != null) {
                selectLaunchProfileEverywhere(selected);
            }
        });
        // Title-bar shortcut — same backing list, selection kept in sync both ways.
        launchProfileTitleBox.setItems(launchProfileBox.getItems());
        launchProfileTitleBox.setConverter(nameConverter(LaunchProfile::name));
        launchProfileTitleBox.setValue(config.getSelectedLaunchProfile());
        launchProfileTitleBox.setOnAction(e -> {
            LaunchProfile selected = launchProfileTitleBox.getValue();
            if (selected != null) {
                selectLaunchProfileEverywhere(selected);
            }
        });
        renameLaunchProfileButton.setOnAction(e -> showRenameLaunchProfileDialog());
        deleteLaunchProfileButton.setOnAction(e -> {
            LaunchProfile selected = launchProfileBox.getValue();
            if (selected == null) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    Messages.get("confirm.deleteLaunchProfile.message", selected.name()), ButtonType.YES, ButtonType.NO);
            confirm.setTitle(Messages.get("confirm.deleteLaunchProfile.title"));
            confirm.setHeaderText(null);
            confirm.showAndWait().filter(response -> response == ButtonType.YES)
                    .ifPresent(response -> onDeleteLaunchProfileClick.accept(selected));
        });
        addLaunchProfileButton.setOnAction(e -> showAddLaunchProfileDialog());
        importLaunchProfileButton.setOnAction(e -> {
            Path file = chooseFile(stage, "*.json");
            if (file != null) {
                onImportLaunchProfileClick.accept(file);
            }
        });
        exportLaunchProfileButton.setOnAction(e -> {
            LaunchProfile selected = launchProfileBox.getValue();
            if (selected == null) {
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName(selected.name() + "-launch-profile.json");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
            File file = chooser.showSaveDialog(stage);
            if (file != null) {
                onExportLaunchProfileClick.accept(selected, file.toPath());
            }
        });

        // Title-bar shortcut for serverBox — see the field declarations' javadoc.
        connectionProfileTitleBox.setItems(serverBox.getItems());
        connectionProfileTitleBox.setConverter(nameConverter(ServerProfile::name));
        connectionProfileTitleBox.setOnAction(e -> {
            ServerProfile selected = connectionProfileTitleBox.getValue();
            if (selected != null) {
                selectServerProfileEverywhere(selected);
            }
        });

        // Title-bar shortcut for serverBox2 — only shown/relevant while host mode is on, see
        // hostModeCheckBox/hostModeTitleCheckBox's visibility toggling below.
        hostProfileTitleBox.setItems(serverBox.getItems());
        hostProfileTitleBox.setConverter(nameConverter(ServerProfile::name));
        hostProfileTitleBox.setOnAction(e -> {
            ServerProfile selected = hostProfileTitleBox.getValue();
            if (selected != null) {
                selectServerProfileEverywhere(selected);
            }
        });

        playerNameField.setText(config.getPlayerName());
        playerNameField.setPromptText(Messages.get("prompt.playerName"));
        playerNameField.setOnAction(e -> onPlayerNameChange.accept(playerNameField.getText().trim()));
        // Enter alone missed every edit that instead ended by clicking Play/switching tabs/etc. —
        // the typed name looked accepted (it's still in the field) but config.playerName never
        // updated, so it silently reverted on the next applyConfig() call. Same fix as
        // commitSpinnerOnFocusLoss below, applied here too.
        playerNameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                onPlayerNameChange.accept(playerNameField.getText().trim());
            }
        });
        msaClientIdField.setText(config.getMsaClientId());
        msaClientIdField.setOnAction(e -> onMsaClientIdChange.accept(msaClientIdField.getText().trim()));
        signInButton.setOnAction(e -> onSignInClick.run());
        signOutButton.setOnAction(e -> onSignOutClick.run());
        msaCancelButton.setOnAction(e -> onCancelSignInClick.run());
        updateAccountLabel(config.isMsaSignedIn(), config.getPlayerName(), config.getMsaCachedUsername());

        updateButton.setOnAction(e -> onUpdateClick.run());
        playButton.setOnAction(e -> onPlayClick.run());
        closeButton.setOnAction(e -> onCloseRequest.run());
        checkForUpdateButton.setOnAction(e -> onCheckForUpdateClick.run());

        managerRefreshButton.setOnAction(e -> onManagerRefreshClick.run());
        managerStartRestartButton.setOnAction(e -> onManagerStartRestartClick.run());
        managerStopButton.setOnAction(e -> onManagerStopClick.run());
        managerBackupNowButton.setOnAction(e -> onManagerBackupNowClick.run());

        hostModeCheckBox.setSelected(config.isHostModeEnabled());
        hostModeTitleCheckBox.setSelected(config.isHostModeEnabled());
        serverDirectoryRow.setVisible(config.isHostModeEnabled());
        serverDirectoryRow.setManaged(config.isHostModeEnabled());
        portForwardingRow.setVisible(config.isHostModeEnabled());
        portForwardingRow.setManaged(config.isHostModeEnabled());
        hostProfileTitleRow.setVisible(config.isHostModeEnabled());
        hostProfileTitleRow.setManaged(config.isHostModeEnabled());
        hostModeCheckBox.setOnAction(e -> {
            boolean enabled = hostModeCheckBox.isSelected();
            hostModeTitleCheckBox.setSelected(enabled); // programmatic — doesn't re-fire its own onAction
            serverDirectoryRow.setVisible(enabled);
            serverDirectoryRow.setManaged(enabled);
            portForwardingRow.setVisible(enabled);
            portForwardingRow.setManaged(enabled);
            hostProfileTitleRow.setVisible(enabled);
            hostProfileTitleRow.setManaged(enabled);
            onHostModeChange.accept(enabled);
        });
        // Same toggle, now living in the quick-access profile row (see the field declarations'
        // javadoc) rather than next to the title — see hostModeCheckBox above.
        hostModeTitleCheckBox.setOnAction(e -> {
            boolean enabled = hostModeTitleCheckBox.isSelected();
            hostModeCheckBox.setSelected(enabled);
            serverDirectoryRow.setVisible(enabled);
            serverDirectoryRow.setManaged(enabled);
            portForwardingRow.setVisible(enabled);
            portForwardingRow.setManaged(enabled);
            hostProfileTitleRow.setVisible(enabled);
            hostProfileTitleRow.setManaged(enabled);
            onHostModeChange.accept(enabled);
        });
        portForwardingCheckBox.setSelected(config.isPortForwardingEnabled());
        portForwardingCheckBox.setOnAction(e -> onPortForwardingChange.accept(portForwardingCheckBox.isSelected()));
        copyExternalIpButton.setOnAction(e -> copyExternalIpToClipboard());
        serverDirectoryField.setText(config.getServerDirectory());
        serverDirectoryField.setOnAction(e -> onServerDirectoryChange.accept(serverDirectoryField.getText().trim()));
        serverDirectoryBrowseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File current = new File(serverDirectoryField.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File chosen = chooser.showDialog(stage);
            if (chosen != null) {
                serverDirectoryField.setText(chosen.getAbsolutePath());
                onServerDirectoryChange.accept(chosen.getAbsolutePath());
            }
        });

        backupsListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(BackupInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Messages.get("backup.entry",
                        item.getFileName(), item.getSizeBytes() / 1024 / 1024,
                        Instant.ofEpochMilli(item.getCreatedAtMillis())));
            }
        });
        cancelScheduledRestartButton.setOnAction(e -> onCancelScheduledRestartClick.run());

        whitelistListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(WhitelistEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Messages.get("whitelist.entry", item.getName(), item.getUuid()));
            }
        });
        addWhitelistButton.setOnAction(e -> {
            String name = whitelistNameField.getText().trim();
            if (!name.isEmpty()) {
                onAddWhitelistClick.accept(name);
                whitelistNameField.clear();
            }
        });
        removeWhitelistButton.setOnAction(e -> withSelected(whitelistListView, entry -> onRemoveWhitelistClick.accept(entry.getName())));

        worldsListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(WorldProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                long megabytes = item.getSizeBytes() / 1024 / 1024;
                setText(item.isActive()
                        ? Messages.get("worlds.entry.active", item.getName(), megabytes)
                        : Messages.get("worlds.entry", item.getName(), megabytes));
            }
        });
        createWorldButton.setOnAction(e -> {
            String name = newWorldNameField.getText().trim();
            if (!name.isEmpty()) {
                onCreateWorldClick.accept(name, cloneActiveWorldCheckBox.isSelected());
                newWorldNameField.clear();
            }
        });
        activateWorldButton.setOnAction(e -> withSelected(worldsListView, profile -> onActivateWorldClick.accept(profile.getName())));
        // newWorldNameField does double duty (create target / rename target), same idiom as
        // serverBox's editable combo for server profile rename — no separate dialog needed.
        renameWorldButton.setOnAction(e -> withSelected(worldsListView, profile -> {
            String newName = newWorldNameField.getText().trim();
            if (!newName.isEmpty() && !newName.equals(profile.getName())) {
                onRenameWorldClick.accept(profile.getName(), newName);
                newWorldNameField.clear();
            }
        }));
        deleteWorldButton.setOnAction(e -> withSelected(worldsListView, profile -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    Messages.get("confirm.deleteWorld.message", profile.getName()), ButtonType.YES, ButtonType.NO);
            confirm.setTitle(Messages.get("confirm.deleteWorld.title"));
            confirm.setHeaderText(null);
            confirm.showAndWait().filter(response -> response == ButtonType.YES)
                    .ifPresent(response -> onDeleteWorldClick.accept(profile.getName()));
        }));

        connectionAttemptsListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionAttempt item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String time = Instant.ofEpochMilli(item.getTimestampMillis()).toString();
                setText(item.isAccepted()
                        ? Messages.get("connectionAttempt.entry.accepted", item.getName(), time)
                        : Messages.get("connectionAttempt.entry.rejected", item.getName(), item.getReason(), time));
            }
        });
        addAttemptToWhitelistButton.setOnAction(e ->
                withSelected(connectionAttemptsListView, attempt -> onAddWhitelistClick.accept(attempt.getName())));

        preventOverwriteCheckBox.setOnAction(e -> onPreventOverwriteChange.accept(preventOverwriteCheckBox.isSelected()));
        resourcePacksPreventOverwriteCheckBox.setOnAction(e ->
                onPreventOverwriteChange.accept(resourcePacksPreventOverwriteCheckBox.isSelected()));
        savePublicGameAddressButton.setOnAction(e ->
                onSavePublicGameAddressClick.accept(publicGameAddressField.getText().trim()));

        // --- Mod tab: three lists (both/server-only/client-only), filtered live off the same
        // modsItems backing list showMods() feeds — see ModCategory. No per-list showCategory
        // badge here since the column itself already says which category it is.
        FilteredList<ManagedFile> modsBothFiltered = new FilteredList<>(modsItems, m -> effectiveCategory(m) == ModCategory.BOTH);
        FilteredList<ManagedFile> modsServerOnlyFiltered = new FilteredList<>(modsItems, m -> effectiveCategory(m) == ModCategory.SERVER_ONLY);
        FilteredList<ManagedFile> modsClientOnlyFiltered = new FilteredList<>(modsItems, m -> effectiveCategory(m) == ModCategory.CLIENT_ONLY);
        modsBothListView.setItems(modsBothFiltered);
        modsServerOnlyListView.setItems(modsServerOnlyFiltered);
        modsClientOnlyListView.setItems(modsClientOnlyFiltered);
        modsBothListView.setCellFactory(lv -> editableManagedFileCell(checkedModNames, false));
        modsServerOnlyListView.setCellFactory(lv -> editableManagedFileCell(checkedModNames, false));
        modsClientOnlyListView.setCellFactory(lv -> editableManagedFileCell(checkedModNames, false));
        // Only one of the three lists is ever "the" selection at a time — picking a row in one
        // clears the other two, so Remove/Enable-Disable/move-to-category (withSelectedMod) always
        // have exactly one unambiguous target.
        exclusiveSelection(modsBothListView, modsServerOnlyListView, modsClientOnlyListView);

        resourcePacksListView.setCellFactory(lv -> editableManagedFileCell(checkedResourcePackNames, false));
        resourcePacksListView.setItems(resourcePacksItems);

        // Play tab: same backing lists, read-only view — see showMods/showResourcePacks. The
        // combined mods list still shows category there since it isn't split into columns.
        playModsListView.setCellFactory(lv -> managedFileCell(true));
        playResourcePacksListView.setCellFactory(lv -> managedFileCell(false));
        playModsListView.setItems(modsItems);
        playResourcePacksListView.setItems(resourcePacksItems);
        playModsListView.setPrefHeight(90);
        playResourcePacksListView.setPrefHeight(90);
        playModsListView.setMaxWidth(Double.MAX_VALUE);
        playResourcePacksListView.setMaxWidth(Double.MAX_VALUE);
        copyModsListButton.setOnAction(e -> copyModsListToClipboard());
        copyResourcePacksListButton.setOnAction(e -> copyResourcePacksListToClipboard());

        addModButton.setOnAction(e -> {
            List<Path> files = chooseFiles(stage, "*.jar");
            if (!files.isEmpty()) {
                onAddModClick.accept(files);
            }
        });
        changeSkinButton.setOnAction(e -> {
            Path file = chooseFile(stage, "*.png");
            if (file != null) {
                onChangeSkinClick.accept(file);
            }
        });
        removeModButton.setOnAction(e -> withSelectedMod(onRemoveModClick));
        toggleModButton.setOnAction(e -> withSelectedMod(onToggleModClick));
        moveModToBothButton.setOnAction(e -> withSelectedMod(file -> onSetModCategoryClick.accept(file, ModCategory.BOTH)));
        moveModToServerOnlyButton.setOnAction(e -> withSelectedMod(file -> onSetModCategoryClick.accept(file, ModCategory.SERVER_ONLY)));
        moveModToClientOnlyButton.setOnAction(e -> withSelectedMod(file -> onSetModCategoryClick.accept(file, ModCategory.CLIENT_ONLY)));
        openModsFolderButton.setOnAction(e -> openLocalServerFolder("mods"));
        selectAllModsButton.setOnAction(e -> selectAll(modsItems, checkedModNames, modsBothListView, modsServerOnlyListView, modsClientOnlyListView));
        selectNoneModsButton.setOnAction(e -> selectNone(checkedModNames, modsBothListView, modsServerOnlyListView, modsClientOnlyListView));
        enableSelectedModsButton.setOnAction(e ->
                onBulkToggleModsClick.accept(checkedNeedingFlip(modsItems, checkedModNames, false)));
        disableSelectedModsButton.setOnAction(e ->
                onBulkToggleModsClick.accept(checkedNeedingFlip(modsItems, checkedModNames, true)));

        addResourcePackButton.setOnAction(e -> {
            List<Path> files = chooseFiles(stage, "*.zip");
            if (!files.isEmpty()) {
                onAddResourcePackClick.accept(files);
            }
        });
        removeResourcePackButton.setOnAction(e -> withSelected(resourcePacksListView, onRemoveResourcePackClick));
        toggleResourcePackButton.setOnAction(e -> withSelected(resourcePacksListView, onToggleResourcePackClick));
        openResourcePacksFolderButton.setOnAction(e -> openLocalServerFolder("resourcepacks"));
        selectAllResourcePacksButton.setOnAction(e -> selectAll(resourcePacksItems, checkedResourcePackNames, resourcePacksListView));
        selectNoneResourcePacksButton.setOnAction(e -> selectNone(checkedResourcePackNames, resourcePacksListView));
        enableSelectedResourcePacksButton.setOnAction(e ->
                onBulkToggleResourcePacksClick.accept(checkedNeedingFlip(resourcePacksItems, checkedResourcePackNames, false)));
        disableSelectedResourcePacksButton.setOnAction(e ->
                onBulkToggleResourcePacksClick.accept(checkedNeedingFlip(resourcePacksItems, checkedResourcePackNames, true)));

        difficultyBox.getItems().addAll("peaceful", "easy", "normal", "hard");
        gamemodeBox.getItems().addAll("survival", "creative", "adventure", "spectator");
        maxPlayersSpinner.setEditable(true);
        viewDistanceSpinner.setEditable(true);
        simulationDistanceSpinner.setEditable(true);
        spawnProtectionSpinner.setEditable(true);
        // An editable Spinner's typed text otherwise only commits to its value on Enter — clicking
        // straight from the text field to Save Settings silently kept the OLD value. Commit
        // whatever was typed as soon as the field loses focus too, so Save always sees it.
        commitSpinnerOnFocusLoss(maxPlayersSpinner);
        commitSpinnerOnFocusLoss(viewDistanceSpinner);
        commitSpinnerOnFocusLoss(simulationDistanceSpinner);
        commitSpinnerOnFocusLoss(spawnProtectionSpinner);

        loadSettingsButton.setOnAction(e -> onLoadSettingsClick.run());
        saveSettingsButton.setOnAction(e -> onSaveSettingsClick.accept(settingsFromFields()));

        retentionCountSpinner.setEditable(true);
        cooldownSpinner.setEditable(true);
        periodicIntervalSpinner.setEditable(true);
        commitSpinnerOnFocusLoss(retentionCountSpinner);
        commitSpinnerOnFocusLoss(cooldownSpinner);
        commitSpinnerOnFocusLoss(periodicIntervalSpinner);
        saveBackupSettingsButton.setOnAction(e -> onSaveBackupSettingsClick.accept(backupSettingsFromFields()));

        restartDelayMinutesBox.getItems().addAll(1, 5, 10, 15, 30);
        restartDelayMinutesBox.setValue(5);
        restartNowButton.setOnAction(e -> onManagerStartRestartClick.run());
        scheduleRestartButton.setOnAction(e -> {
            Integer minutes = restartDelayMinutesBox.getValue();
            if (minutes != null) {
                onScheduleRestartClick.accept(minutes * 60L);
            }
        });
        dismissPendingChangesButton.setOnAction(e -> setPendingChangesVisible(false));

        logArea.setEditable(false);
        logArea.setWrapText(true);
        serverLogArea.setEditable(false);
        serverLogArea.setWrapText(true);
        managerLogArea.setEditable(false);
        managerLogArea.setWrapText(true);
        modsSettingsLogArea.setEditable(false);
        modsSettingsLogArea.setWrapText(true);
        resourcePacksLogArea.setEditable(false);
        resourcePacksLogArea.setWrapText(true);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        currentServerLabel.setStyle("-fx-font-style: italic;");
        HBox.setHgrow(installDirField, Priority.ALWAYS);
        HBox.setHgrow(serverBox, Priority.ALWAYS);

        refreshSyncIndicator(); // initial "checking" state before the first checkForUpdates() completes
        setServerConnectable(false); // initial state before the first status fetch completes

        HBox topBar = new HBox(10, titleLabel, syncStatusIndicator, serverConnectableIndicator,
                spacer(), languageLabel, languageBox);
        topBar.setAlignment(Pos.CENTER_LEFT);

        // Quick-access profile row — see the field declarations' javadoc.
        hostProfileTitleRow.getChildren().setAll(hostProfileTitleLabel, hostProfileTitleBox);
        hostProfileTitleRow.setAlignment(Pos.CENTER_LEFT);
        HBox profileBar = new HBox(15,
                launchProfileTitleLabel, launchProfileTitleBox,
                connectionProfileTitleLabel, connectionProfileTitleBox,
                hostModeTitleCheckBox, hostProfileTitleRow);
        profileBar.setAlignment(Pos.CENTER_LEFT);

        launcherUpdateRow.getChildren().setAll(launcherUpdateLabel, downloadUpdateButton);
        launcherUpdateRow.setAlignment(Pos.CENTER_LEFT);
        launcherUpdateRow.setStyle("-fx-background-color: #fff3cd; -fx-padding: 8; -fx-background-radius: 4;");
        launcherUpdateRow.setVisible(false);
        launcherUpdateRow.setManaged(false);

        // --- Play tab: daily use, no server-config fields ---
        HBox installDirRow = new HBox(10, installDirLabel, installDirField, browseButton);
        installDirRow.setAlignment(Pos.CENTER_LEFT);

        // On its own row below install dir, not squeezed in beside it — see button.reset's
        // renamed label ("reset the launch environment back to vanilla").
        HBox resetRow = new HBox(10, resetButton, checkForUpdateButton);
        resetRow.setAlignment(Pos.CENTER_LEFT);

        // Button order/wording matches serverPresetRow — see its comment: 新規作成/名前変更/読み込み/書き出し/削除.
        FlowPane launchProfileRow = new FlowPane(10, 6, launchProfileLabel, launchProfileBox,
                addLaunchProfileButton, renameLaunchProfileButton, importLaunchProfileButton,
                exportLaunchProfileButton, deleteLaunchProfileButton);
        launchProfileRow.setAlignment(Pos.CENTER_LEFT);

        HBox playerNameRow = new HBox(10, playerNameLabel, playerNameField, changeSkinButton);
        playerNameRow.setAlignment(Pos.CENTER_LEFT);
        // Microsoft sign-in controls (Client ID field, Sign in/out buttons,
        // device-code banner) are intentionally left out of this layout —
        // this build's server runs online-mode=false, so only the offline
        // player name matters here. The underlying fields/handlers still
        // exist (see updateAccountLabel/showMsaDeviceCodePrompt) in case
        // MSA sign-in needs to come back; they're just never added to the
        // scene graph, so they never render regardless of signed-in state.
        VBox accountBox = new VBox(6, accountHeaderLabel, accountStatusLabel, playerNameRow);

        HBox playButtonRow = new HBox(10, updateButton, playButton, closeButton);

        HBox currentServerRow = new HBox(10, currentServerLabel, toggleCurrentServerAddressButton);
        currentServerRow.setAlignment(Pos.CENTER_LEFT);
        toggleCurrentServerAddressButton.setOnAction(e -> {
            currentServerAddressVisible = !currentServerAddressVisible;
            refreshCurrentServerLabel();
        });

        // Mods and resource packs stacked into one column (each with its own Copy List button —
        // see copyResourcePacksListToClipboard), placed beside the rest of the tab's controls
        // rather than inline among them.
        HBox playModsLabelRow = new HBox(10, playModsLabel, copyModsListButton);
        playModsLabelRow.setAlignment(Pos.CENTER_LEFT);
        HBox playResourcePacksLabelRow = new HBox(10, playResourcePacksLabel, copyResourcePacksListButton);
        playResourcePacksLabelRow.setAlignment(Pos.CENTER_LEFT);
        VBox playModsColumn = new VBox(6, playModsLabelRow, playModsListView);
        VBox playResourcePacksColumn = new VBox(6, playResourcePacksLabelRow, playResourcePacksListView);
        VBox playFilesColumn = new VBox(10, playModsColumn, playResourcePacksColumn);
        playFilesColumn.setPrefWidth(260);

        VBox playSettingsColumn = new VBox(10, installDirRow, resetRow, currentServerRow, launchProfileRow,
                accountBox,
                statusLabel, progressBar, playButtonRow);
        playSettingsColumn.setPrefWidth(260);

        HBox playColumns = new HBox(15, playSettingsColumn, playFilesColumn);
        HBox.setHgrow(playSettingsColumn, Priority.ALWAYS);
        HBox.setHgrow(playFilesColumn, Priority.ALWAYS);

        VBox playControls = new VBox(10, playColumns);
        playControls.setPrefWidth(420);

        HBox playLogHeaderRow = new HBox(10, playLogLabel, spacer(), playLogCollapseButton, playLogClearButton);
        playLogHeaderRow.setAlignment(Pos.CENTER_LEFT);
        VBox playLogPane = new VBox(6, playLogHeaderRow, logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        SplitPane playContent = logSplit(playControls, playLogPane, 0.65);
        wireLogCollapse(playLogCollapseButton, logArea, playContent, 0.65);
        playLogClearButton.setOnAction(e -> logArea.clear());
        playContent.setPadding(new Insets(15));
        playTab.setContent(playContent);
        playTab.setClosable(false);

        // --- Server tab: editable connection form, touched once per server. No API-key Generate
        // button here — this field is where you paste a key someone else's server already
        // requires, not somewhere to mint a new one (see Server Management's own Generate
        // buttons for setting up a server you host yourself). ---
        // FlowPane, not HBox — this tab is narrow, and serverBox + 4 buttons doesn't fit one row;
        // wrapping is better than a horizontal scrollbar.
        // Button order/wording matches every other profile picker in the app — see the field
        // declarations' comments: 新規作成/名前変更/読み込み/書き出し/削除 (add/rename/import/export/delete).
        FlowPane serverPresetRow = new FlowPane(10, 6, serverPresetLabel, serverBox,
                addProfileButton, renameProfileButton, importProfileButton, exportProfileButton, deleteProfileButton);

        HBox gameAddressRow = maskableRow(gameAddressField, gameAddressMaskField, gameAddressRevealButton);
        gameAddressRow.getChildren().add(fetchGameAddressButton);

        // One field per row (not label+field pairs side by side) — this tab is narrow, and two
        // fields sharing a row left neither with enough width to be legible.
        GridPane serverFieldsGrid = new GridPane();
        serverFieldsGrid.setHgap(10);
        serverFieldsGrid.setVgap(6);
        serverFieldsGrid.addRow(0, apiUrlLabel, maskableRow(apiUrlField, apiUrlMaskField, apiUrlRevealButton));
        serverFieldsGrid.addRow(1, gameAddressLabel, gameAddressRow);
        serverFieldsGrid.addRow(2, mcVersionLabel, mcVersionField);
        serverFieldsGrid.addRow(3, loaderVersionLabel, loaderVersionField);
        serverFieldsGrid.addRow(4, apiKeyLabel, apiKeyField);
        ColumnConstraints labelCol = new ColumnConstraints();
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        serverFieldsGrid.getColumnConstraints().addAll(labelCol, fieldCol);

        HBox serverButtonRow = new HBox(10, connectButton, savePresetButton);

        VBox serverControls = new VBox(10, serverPresetRow, serverFieldsGrid, serverButtonRow);
        serverControls.setPrefWidth(340);

        HBox serverLogHeaderRow = new HBox(10, serverLogLabel, spacer(), serverLogCollapseButton, serverLogClearButton);
        serverLogHeaderRow.setAlignment(Pos.CENTER_LEFT);
        VBox serverLogPane = new VBox(6, serverLogHeaderRow, serverLogArea);
        VBox.setVgrow(serverLogArea, Priority.ALWAYS);

        SplitPane serverContent = logSplit(serverControls, serverLogPane, 0.65);
        wireLogCollapse(serverLogCollapseButton, serverLogArea, serverContent, 0.65);
        serverLogClearButton.setOnAction(e -> serverLogArea.clear());
        serverContent.setPadding(new Insets(15));
        serverTab.setContent(serverContent);
        serverTab.setClosable(false);

        // --- Server Management tab: live status + start/stop/restart actions ---
        serverDirectoryRow.getChildren().setAll(serverDirectoryLabel, serverDirectoryField, serverDirectoryBrowseButton);
        serverDirectoryRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(serverDirectoryField, Priority.ALWAYS);

        portForwardingRow.getChildren().setAll(portForwardingCheckBox, portForwardingStatusLabel, portForwardingStatusValue, copyExternalIpButton);
        portForwardingRow.setAlignment(Pos.CENTER_LEFT);
        copyExternalIpButton.setVisible(false);
        copyExternalIpButton.setManaged(false);

        // Basic connection settings + access-key generation for the server this PC hosts —
        // same fields as the Server tab, but with Generate buttons (see the field declarations'
        // javadoc for why that belongs here and not there).
        HBox apiKeyRow2 = new HBox(6, apiKeyField2, generateApiKeyButton);
        HBox.setHgrow(apiKeyField2, Priority.ALWAYS);
        HBox managerApiKeyRow2 = new HBox(6, managerApiKeyField2, generateManagerApiKeyButton);
        HBox.setHgrow(managerApiKeyField2, Priority.ALWAYS);

        HBox hostProfileRow = new HBox(10, hostProfileLabel, serverBox2);
        hostProfileRow.setAlignment(Pos.CENTER_LEFT);

        // One field per row (not two side by side) — same width concern as the Server tab's
        // serverFieldsGrid, and this column's narrower still (it shares the tab with the status
        // column — see managerSettingsColumn below).
        GridPane hostSettingsGrid = new GridPane();
        hostSettingsGrid.setHgap(10);
        hostSettingsGrid.setVgap(6);
        hostSettingsGrid.addRow(0, apiUrlLabel2, maskableRow(apiUrlField2, apiUrlMaskField2, apiUrlRevealButton2));
        hostSettingsGrid.addRow(1, gameAddressLabel2, maskableRow(gameAddressField2, gameAddressMaskField2, gameAddressRevealButton2));
        hostSettingsGrid.addRow(2, mcVersionLabel2, mcVersionField2);
        hostSettingsGrid.addRow(3, loaderVersionLabel2, loaderVersionField2);
        hostSettingsGrid.addRow(4, apiKeyLabel2, apiKeyRow2);
        hostSettingsGrid.addRow(5, managerUrlLabel2, maskableRow(managerUrlField2, managerUrlMaskField2, managerUrlRevealButton2));
        hostSettingsGrid.addRow(6, managerApiKeyLabel2, managerApiKeyRow2);
        ColumnConstraints hostLabelCol = new ColumnConstraints();
        ColumnConstraints hostFieldCol = new ColumnConstraints();
        hostFieldCol.setHgrow(Priority.ALWAYS);
        hostSettingsGrid.getColumnConstraints().addAll(hostLabelCol, hostFieldCol);

        VBox hostSettingsColumn = new VBox(6, hostProfileRow, hostSettingsLabel, hostSettingsGrid, saveHostSettingsButton);

        // Moved here from the Mod tab — it's a connection setting (what address sync-server hands
        // out to a fetching client, see onFetchPublicGameAddress), not mod-admin. Not masked, unlike
        // the fields above: this one exists specifically to be handed out.
        HBox publicGameAddressRow = new HBox(6, publicGameAddressLabel, publicGameAddressField, savePublicGameAddressButton);
        publicGameAddressRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(publicGameAddressField, Priority.ALWAYS);

        GridPane managerStatusGrid = new GridPane();
        managerStatusGrid.setHgap(10);
        managerStatusGrid.setVgap(6);
        managerStatusGrid.addRow(0, managerStateLabel, managerStateValue);
        managerStatusGrid.addRow(1, managerUptimeLabel, managerUptimeValue);
        managerStatusGrid.addRow(2, managerPidLabel, managerPidValue);
        managerStatusGrid.addRow(3, managerLastBackupLabel, managerLastBackupValue);
        managerStatusGrid.addRow(4, managerCrashCountLabel, managerCrashCountValue);

        HBox managerButtonRow = new HBox(10, managerRefreshButton, managerStartRestartButton, managerStopButton);

        HBox scheduledRestartRow = new HBox(10, scheduledRestartLabel, scheduledRestartValue, cancelScheduledRestartButton);
        scheduledRestartRow.setAlignment(Pos.CENTER_LEFT);

        // Left column: status readout + the actions that act on it right now. Right column: the
        // settings that configure how/whether this PC hosts at all — host-mode toggle first, then
        // the folder it hosts from, with port auto-forwarding last since it only matters once
        // host mode + a folder are already set, and the connection-file export as the final,
        // occasional-use action at the very bottom.
        VBox managerStatusColumn = new VBox(10, managerStatusGrid, managerButtonRow, managerStatusLabel, scheduledRestartRow);
        managerStatusColumn.setPrefWidth(260);

        HBox exportConnectionFileRow = new HBox(10, exportConnectionFileButton, exportConnectionFileEncryptedButton);

        VBox managerSettingsColumn = new VBox(10,
                hostSettingsColumn, publicGameAddressRow, hostModeCheckBox, serverDirectoryRow, portForwardingRow, exportConnectionFileRow);
        managerSettingsColumn.setPrefWidth(340);

        HBox managerColumns = new HBox(20, managerStatusColumn, managerSettingsColumn);
        HBox.setHgrow(managerStatusColumn, Priority.ALWAYS);
        HBox.setHgrow(managerSettingsColumn, Priority.ALWAYS);

        ScrollPane managerControlsScroll = new ScrollPane(managerColumns);
        managerControlsScroll.setFitToWidth(true);
        managerControlsScroll.setPrefWidth(620);

        HBox managerLogHeaderRow = new HBox(10, managerLogLabel, spacer(), managerLogCollapseButton, managerLogClearButton);
        managerLogHeaderRow.setAlignment(Pos.CENTER_LEFT);
        VBox managerLogPane = new VBox(6, managerLogHeaderRow, managerLogArea);
        VBox.setVgrow(managerLogArea, Priority.ALWAYS);

        SplitPane managerContent = logSplit(managerControlsScroll, managerLogPane, 0.6);
        wireLogCollapse(managerLogCollapseButton, managerLogArea, managerContent, 0.6);
        managerLogClearButton.setOnAction(e -> managerLogArea.clear());
        managerContent.setPadding(new Insets(15));
        serverManagerTab.setContent(managerContent);
        serverManagerTab.setClosable(false);

        // --- Player tab: who's online, the whitelist, and recent join attempts — three
        // side-by-side columns (previously stacked vertically) ---
        onlinePlayersListView.setPrefHeight(220);

        whitelistListView.setPrefHeight(220);
        HBox.setHgrow(whitelistNameField, Priority.ALWAYS);
        HBox whitelistAddRow = new HBox(10, whitelistNameField, addWhitelistButton, removeWhitelistButton);
        whitelistAddRow.setAlignment(Pos.CENTER_LEFT);

        connectionAttemptsListView.setPrefHeight(220);

        VBox onlinePlayersColumn = new VBox(10, onlinePlayersLabel, onlinePlayersListView);
        VBox whitelistColumn = new VBox(10, whitelistLabel, whitelistListView, whitelistAddRow);
        VBox connectionAttemptsColumn = new VBox(10, connectionAttemptsLabel, connectionAttemptsListView, addAttemptToWhitelistButton);
        VBox.setVgrow(onlinePlayersListView, Priority.ALWAYS);
        VBox.setVgrow(whitelistListView, Priority.ALWAYS);
        VBox.setVgrow(connectionAttemptsListView, Priority.ALWAYS);
        HBox.setHgrow(onlinePlayersColumn, Priority.ALWAYS);
        HBox.setHgrow(whitelistColumn, Priority.ALWAYS);
        HBox.setHgrow(connectionAttemptsColumn, Priority.ALWAYS);

        HBox playerColumns = new HBox(15, onlinePlayersColumn, whitelistColumn, connectionAttemptsColumn);
        ScrollPane playerContent = new ScrollPane(playerColumns);
        playerContent.setFitToWidth(true);
        playerContent.setFitToHeight(true);
        playerContent.setPadding(new Insets(15));
        playerTab.setContent(playerContent);
        playerTab.setClosable(false);

        // --- Backup tab: left column takes a backup and browses past ones (unchanged); right
        // column is the backup policy — retention/triggers/cooldown/participant permission. ---
        VBox.setVgrow(backupsListView, Priority.ALWAYS);
        VBox backupActionsColumn = new VBox(10, managerBackupNowButton, backupsListLabel, backupsListView);
        backupActionsColumn.setPrefWidth(320);

        GridPane backupSettingsGrid = new GridPane();
        backupSettingsGrid.setHgap(10);
        backupSettingsGrid.setVgap(8);
        backupSettingsGrid.addRow(0, retentionCountLabel, retentionCountSpinner);
        backupSettingsGrid.addRow(1, backupOnLoginCheckBox);
        backupSettingsGrid.addRow(2, backupOnLogoutCheckBox);
        backupSettingsGrid.addRow(3, cooldownLabel, cooldownSpinner);
        backupSettingsGrid.addRow(4, backupPeriodicCheckBox);
        backupSettingsGrid.addRow(5, periodicIntervalLabel, periodicIntervalSpinner);
        backupSettingsGrid.addRow(6, allowParticipantBackupCheckBox);
        GridPane.setColumnSpan(backupOnLoginCheckBox, 2);
        GridPane.setColumnSpan(backupOnLogoutCheckBox, 2);
        GridPane.setColumnSpan(backupPeriodicCheckBox, 2);
        GridPane.setColumnSpan(allowParticipantBackupCheckBox, 2);

        VBox backupSettingsColumn = new VBox(10, backupSettingsLabel, backupSettingsGrid, saveBackupSettingsButton);
        backupSettingsColumn.setPrefWidth(320);

        HBox backupColumns = new HBox(20, backupActionsColumn, backupSettingsColumn);
        HBox.setHgrow(backupActionsColumn, Priority.ALWAYS);
        HBox.setHgrow(backupSettingsColumn, Priority.ALWAYS);

        ScrollPane backupContent = new ScrollPane(backupColumns);
        backupContent.setFitToWidth(true);
        backupContent.setPadding(new Insets(15));
        backupTab.setContent(backupContent);
        backupTab.setClosable(false);

        // --- Worlds tab: switch/create/rename/delete server/<name>/ world saves — see
        // WorldProfileService. newWorldNameField/cloneActiveWorldCheckBox feed Create, and
        // newWorldNameField is reused as the Rename target (see renameWorldButton wiring above). ---
        worldsListView.setPrefHeight(260);
        VBox.setVgrow(worldsListView, Priority.ALWAYS);
        HBox worldsCreateRow = new HBox(10, newWorldNameField, cloneActiveWorldCheckBox, createWorldButton);
        worldsCreateRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(newWorldNameField, Priority.ALWAYS);
        HBox worldsActionRow = new HBox(10, activateWorldButton, renameWorldButton, deleteWorldButton);

        VBox worldsColumn = new VBox(10, worldsLabel, worldsListView, worldsCreateRow, worldsActionRow);
        ScrollPane worldsContent = new ScrollPane(worldsColumn);
        worldsContent.setFitToWidth(true);
        worldsContent.setPadding(new Insets(15));
        worldsTab.setContent(worldsContent);
        worldsTab.setClosable(false);

        // --- Mod tab: server-side mod admin, split into three resizable columns by ModCategory ---
        VBox.setVgrow(modsBothListView, Priority.ALWAYS);
        VBox.setVgrow(modsServerOnlyListView, Priority.ALWAYS);
        VBox.setVgrow(modsClientOnlyListView, Priority.ALWAYS);
        HBox modsButtonRow = new HBox(10, addModButton, removeModButton, toggleModButton, openModsFolderButton);
        HBox modsMoveRow = new HBox(10, moveModToBothButton, moveModToServerOnlyButton, moveModToClientOnlyButton);
        FlowPane modsBulkRow = new FlowPane(10, 6, selectAllModsButton, selectNoneModsButton,
                enableSelectedModsButton, disableSelectedModsButton);

        VBox modsBothColumn = new VBox(10, modsBothLabel, modsBothListView);
        VBox modsServerOnlyColumn = new VBox(10, modsServerOnlyLabel, modsServerOnlyListView);
        VBox modsClientOnlyColumn = new VBox(10, modsClientOnlyLabel, modsClientOnlyListView);
        modsBothColumn.setMinWidth(180);
        modsServerOnlyColumn.setMinWidth(180);
        modsClientOnlyColumn.setMinWidth(180);

        // Side-by-side, player-resizable columns — drag the dividers to shrink/grow any list's visible range.
        SplitPane modColumnsSplit = new SplitPane(modsBothColumn, modsServerOnlyColumn, modsClientOnlyColumn);
        modColumnsSplit.setDividerPositions(0.34, 0.67);
        VBox.setVgrow(modColumnsSplit, Priority.ALWAYS);

        // preventOverwriteCheckBox (the same-name-mod upload setting) is last — a settings toggle,
        // not something that belongs above the actual mod list it affects. The public game address
        // field used to live here too, but it's a connection setting, not a mod-admin one — see
        // its new home in the Server Manager tab's settings column.
        VBox modControls = new VBox(10, modColumnsSplit, modsButtonRow, modsMoveRow, modsBulkRow, preventOverwriteCheckBox);

        HBox modLogHeaderRow = new HBox(10, modsSettingsLogLabel, spacer(), modsSettingsLogCollapseButton, modsSettingsLogClearButton);
        modLogHeaderRow.setAlignment(Pos.CENTER_LEFT);
        VBox modLogPane = new VBox(6, modLogHeaderRow, modsSettingsLogArea);
        VBox.setVgrow(modsSettingsLogArea, Priority.ALWAYS);

        SplitPane modContent = logSplit(modControls, modLogPane, 0.7);
        wireLogCollapse(modsSettingsLogCollapseButton, modsSettingsLogArea, modContent, 0.7);
        modsSettingsLogClearButton.setOnAction(e -> modsSettingsLogArea.clear());
        modContent.setPadding(new Insets(15));
        modTab.setContent(modContent);
        modTab.setClosable(false);

        // --- Resource Pack tab: same admin shape as the old combined Mod tab's second column ---
        VBox.setVgrow(resourcePacksListView, Priority.ALWAYS);
        HBox resourcePacksButtonRow = new HBox(10, addResourcePackButton, removeResourcePackButton,
                toggleResourcePackButton, openResourcePacksFolderButton);
        FlowPane resourcePacksBulkRow = new FlowPane(10, 6, selectAllResourcePacksButton, selectNoneResourcePacksButton,
                enableSelectedResourcePacksButton, disableSelectedResourcePacksButton);

        VBox resourcePacksControls = new VBox(10, resourcePacksPreventOverwriteCheckBox,
                resourcePacksListLabel, resourcePacksListView, resourcePacksButtonRow, resourcePacksBulkRow);

        HBox resourcePacksLogHeaderRow = new HBox(10, resourcePacksLogLabel, spacer(), resourcePacksLogCollapseButton, resourcePacksLogClearButton);
        resourcePacksLogHeaderRow.setAlignment(Pos.CENTER_LEFT);
        VBox resourcePacksLogPane = new VBox(6, resourcePacksLogHeaderRow, resourcePacksLogArea);
        VBox.setVgrow(resourcePacksLogArea, Priority.ALWAYS);

        SplitPane resourcePacksContent = logSplit(resourcePacksControls, resourcePacksLogPane, 0.7);
        wireLogCollapse(resourcePacksLogCollapseButton, resourcePacksLogArea, resourcePacksContent, 0.7);
        resourcePacksLogClearButton.setOnAction(e -> resourcePacksLogArea.clear());
        resourcePacksContent.setPadding(new Insets(15));
        resourcePacksTab.setContent(resourcePacksContent);
        resourcePacksTab.setClosable(false);

        // --- Settings tab: curated server.properties form ---
        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(10);
        settingsGrid.setVgap(6);
        settingsGrid.addRow(0, difficultyLabel, difficultyBox, gamemodeLabel, gamemodeBox);
        settingsGrid.addRow(1, motdLabel, motdField);
        settingsGrid.addRow(2, maxPlayersLabel, maxPlayersSpinner, viewDistanceLabel, viewDistanceSpinner);
        settingsGrid.addRow(3, simulationDistanceLabel, simulationDistanceSpinner, spawnProtectionLabel, spawnProtectionSpinner);
        settingsGrid.addRow(4, whitelistCheckBox, hardcoreCheckBox, allowFlightCheckBox);
        GridPane.setColumnSpan(motdField, 3);
        ColumnConstraints settingsLabelCol = new ColumnConstraints();
        ColumnConstraints settingsFieldCol = new ColumnConstraints();
        settingsFieldCol.setHgrow(Priority.ALWAYS);
        settingsGrid.getColumnConstraints().addAll(settingsLabelCol, settingsFieldCol, settingsLabelCol, settingsFieldCol);

        HBox settingsButtonRow = new HBox(10, loadSettingsButton, saveSettingsButton);

        pendingChangesRow.getChildren().setAll(pendingChangesLabel, restartDelayMinutesBox, scheduleRestartButton, restartNowButton, dismissPendingChangesButton);
        pendingChangesRow.setAlignment(Pos.CENTER_LEFT);
        pendingChangesRow.setStyle("-fx-background-color: #fff3cd; -fx-padding: 8; -fx-background-radius: 4;");
        pendingChangesRow.setVisible(false);
        pendingChangesRow.setManaged(false);

        VBox settingsControls = new VBox(10,
                settingsLabel, settingsGrid, settingsNoteLabel, settingsButtonRow,
                pendingChangesRow);
        ScrollPane settingsContent = new ScrollPane(settingsControls);
        settingsContent.setFitToWidth(true);
        settingsContent.setPadding(new Insets(15));
        settingsTab.setContent(settingsContent);
        settingsTab.setClosable(false);

        // Two top-level groups: this PC's own client-side concerns (sync/play, which server to
        // connect to) vs. everything about hosting/administering a server. Each inner TabPane
        // keeps its sub-tabs' own content/wiring untouched — only the nesting is new.
        TabPane clientTabs = new TabPane(playTab, serverTab);
        clientGroupTab.setContent(clientTabs);
        clientGroupTab.setClosable(false);

        TabPane serverTabs = new TabPane(serverManagerTab, playerTab, backupTab, worldsTab, modTab, resourcePacksTab, settingsTab);
        serverGroupTab.setContent(serverTabs);
        serverGroupTab.setClosable(false);

        TabPane tabs = new TabPane(clientGroupTab, serverGroupTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox root = new VBox(10, topBar, profileBar, launcherUpdateRow, tabs);
        root.setPadding(new Insets(15));

        refreshTexts();
        updateCurrentServerLabel(config.getActiveServer());

        stage.setScene(new Scene(root, 980, 640));
        // Always consume — whether this actually exits, minimizes, or does
        // nothing is entirely up to onCloseRequest (LauncherApp decides
        // based on host-mode state); Platform.exit() shuts everything down
        // regardless of whether the WindowEvent itself was consumed.
        stage.setOnCloseRequest(e -> {
            e.consume();
            onCloseRequest.run();
        });
        stage.show();
    }

    /** Re-reads install dir / active server / launch profile from config — e.g. after a reset. */
    public void applyConfig(LauncherConfig config) {
        installDirField.setText(config.getInstallDir().toString());
        populateServerFields(config.getActiveServer());
        updateCurrentServerLabel(config.getActiveServer());
        connectionProfileTitleBox.setValue(config.getActiveServer());
        hostProfileTitleBox.setValue(config.getActiveServer());
        launchProfileBox.setValue(config.getSelectedLaunchProfile());
        launchProfileTitleBox.setValue(config.getSelectedLaunchProfile());
        playerNameField.setText(config.getPlayerName());
        msaClientIdField.setText(config.getMsaClientId());
        updateAccountLabel(config.isMsaSignedIn(), config.getPlayerName(), config.getMsaCachedUsername());
    }

    /**
     * Syncs which saved profiles should have their connection details hidden in the Server tab
     * (see hiddenDetailProfileNames/applyMaskedOrRealText) — call once at startup and again
     * whenever LauncherConfig's own set changes (currently: after an encrypted import). Re-applies
     * to whichever profile is currently shown so an already-open Server tab updates immediately.
     */
    public void setHiddenDetailProfileNames(Set<String> names) {
        hiddenDetailProfileNames.clear();
        hiddenDetailProfileNames.addAll(names);
        populateServerFields(currentServerShown);
    }

    /**
     * Resyncs the launch-profile picker(s) after LauncherConfig's own list changed — currently only
     * needed after a player-name edit, since that replaces the selected LaunchProfile in place (see
     * LauncherConfig.setPlayerName) and the ComboBoxes' own item lists otherwise go stale.
     */
    public void refreshLaunchProfiles(List<LaunchProfile> profiles, LaunchProfile active) {
        launchProfileBox.getItems().setAll(profiles);
        launchProfileBox.setValue(active);
        launchProfileTitleBox.setValue(active);
    }

    /** Full resync of the saved-profiles picker(s) — call after a delete/rename changed the list itself. */
    public void refreshServerProfiles(List<ServerProfile> servers, ServerProfile active) {
        serverBox.getItems().setAll(servers);
        serverBox.setValue(active);
        serverBox2.setValue(active);
        connectionProfileTitleBox.setValue(active);
        hostProfileTitleBox.setValue(active);
        populateServerFields(active);
    }

    /**
     * Adds (or replaces, by name) {@code profile} in the saved-profiles list and selects it —
     * without changing the active server, so importing a friend's connection file just makes it
     * available to review/Connect rather than switching to it immediately.
     */
    public void selectServerProfile(ServerProfile profile) {
        serverBox.getItems().removeIf(s -> s.name().equals(profile.name()));
        serverBox.getItems().add(profile);
        serverBox.setValue(profile);
        serverBox2.setValue(profile);
        connectionProfileTitleBox.setValue(profile);
        hostProfileTitleBox.setValue(profile);
        populateServerFields(profile);
    }

    /** Call after a successful Connect so the Play tab reflects the new active server. */
    public void updateCurrentServerLabel(ServerProfile server) {
        currentServerShown = server;
        refreshCurrentServerLabel();
    }

    /**
     * Renders currentServerLabel from {@link #currentServerShown} — just the name unless
     * {@link #currentServerAddressVisible} is on, matching the Server tab's maskable URL fields:
     * the actual connect address isn't left on screen by default (see toggleCurrentServerAddressButton).
     * Uses the same button.revealValue/button.hideValue wording as every other maskable field in
     * the app (see wireMaskable) so the reveal/hide behavior reads identically everywhere.
     */
    private void refreshCurrentServerLabel() {
        currentServerLabel.setText(currentServerAddressVisible
                ? Messages.get("label.currentServer", currentServerShown.name(), currentServerShown.serverAddress())
                : Messages.get("label.currentServer.hidden", currentServerShown.name()));
        toggleCurrentServerAddressButton.setText(Messages.get(
                currentServerAddressVisible ? "button.hideValue" : "button.revealValue"));
    }

    /**
     * Shows the dismissible-by-nature (just stays until closed) banner
     * above the tabs. When {@code assetUrl} is non-null (Windows,
     * self-update supported, and the release has a matching zip attached
     * — see {@link dev.haraguro.modserverplaymanager.launcher.update.UpdateChecker}) the button
     * triggers an in-place self-update instead of just opening the browser.
     */
    public void showLauncherUpdateAvailable(String version, String releaseUrl, String assetUrl) {
        pendingUpdateVersion = version;
        pendingUpdateAssetUrl = assetUrl;
        launcherUpdateLabel.setText(Messages.get("notice.updateAvailable", version));
        if (assetUrl != null) {
            downloadUpdateButton.setText(Messages.get("button.updateNow"));
            downloadUpdateButton.setOnAction(e -> onUpdateNowClick.accept(assetUrl));
        } else {
            downloadUpdateButton.setText(Messages.get("button.downloadUpdate"));
            downloadUpdateButton.setOnAction(e -> {
                try {
                    Desktop.getDesktop().browse(URI.create(releaseUrl));
                } catch (Exception ex) {
                    log(Messages.get("log.failed", ex.getMessage()));
                }
            });
        }
        launcherUpdateRow.setVisible(true);
        launcherUpdateRow.setManaged(true);
    }

    /** Reflects sign-in state: the offline name field/label pair when signed out, the Microsoft account when signed in. */
    public void updateAccountLabel(boolean signedIn, String offlineName, String msaUsername) {
        this.accountSignedIn = signedIn;
        this.accountOfflineName = offlineName == null ? "" : offlineName;
        this.accountMsaUsername = msaUsername == null ? "" : msaUsername;
        applyAccountStatusText();

        signInButton.setVisible(!signedIn);
        signInButton.setManaged(!signedIn);
        signOutButton.setVisible(signedIn);
        signOutButton.setManaged(signedIn);
        playerNameField.setVisible(!signedIn);
        playerNameField.setManaged(!signedIn);
        playerNameLabel.setVisible(!signedIn);
        playerNameLabel.setManaged(!signedIn);
    }

    private void applyAccountStatusText() {
        accountStatusLabel.setText(accountSignedIn
                ? Messages.get("label.accountMicrosoft", accountMsaUsername)
                : Messages.get("label.accountOffline", accountOfflineName));
    }

    /** Shows the user-code + "open browser" banner for the duration of an interactive Microsoft sign-in. */
    public void showMsaDeviceCodePrompt(String userCode, String verificationUri) {
        msaDeviceCodeLabel.setText(Messages.get("msa.deviceCode.instructions", verificationUri, userCode));
        msaOpenBrowserButton.setOnAction(e -> {
            try {
                Desktop.getDesktop().browse(URI.create(verificationUri));
            } catch (Exception ex) {
                log(Messages.get("log.failed", ex.getMessage()));
            }
        });
        msaDeviceCodeRow.setVisible(true);
        msaDeviceCodeRow.setManaged(true);
    }

    public void hideMsaDeviceCodePrompt() {
        msaDeviceCodeRow.setVisible(false);
        msaDeviceCodeRow.setManaged(false);
    }

    /**
     * The single source of truth for "a server profile picker's selection changed" — updates all
     * four pickers (serverBox, serverBox2, connectionProfileTitleBox, hostProfileTitleBox) to the
     * same value (setValue() doesn't re-fire onAction, so no feedback loop), repopulates the
     * connection form, and notifies onViewProfileChange. Called from every one of those pickers'
     * own onAction handler.
     */
    private void selectServerProfileEverywhere(ServerProfile selected) {
        serverBox.setValue(selected);
        serverBox2.setValue(selected);
        connectionProfileTitleBox.setValue(selected);
        hostProfileTitleBox.setValue(selected);
        populateServerFields(selected);
        onViewProfileChange.accept(selected);
    }

    /** Same shape as {@link #selectServerProfileEverywhere} but for the two launch-profile pickers. */
    private void selectLaunchProfileEverywhere(LaunchProfile selected) {
        launchProfileBox.setValue(selected);
        launchProfileTitleBox.setValue(selected);
        playerNameField.setText(selected.playerName() == null ? "" : selected.playerName());
        onLaunchProfileChange.accept(selected);
    }

    private void populateServerFields(ServerProfile server) {
        boolean hidden = hiddenDetailProfileNames.contains(server.name());
        serverBox.getEditor().setText(server.name());
        applyMaskedOrRealText(apiUrlField, server.apiBaseUrl(), hidden);
        applyMaskedOrRealText(gameAddressField, server.serverAddress(), hidden);
        applyMaskedOrRealText(apiKeyField, server.apiKey() == null ? "" : server.apiKey(), hidden);
        apiUrlRevealButton.setDisable(hidden);
        gameAddressRevealButton.setDisable(hidden);
        mcVersionField.setText(server.minecraftVersion());
        loaderVersionField.setText(server.fabricLoaderVersion());
        // Not shown/edited on the Server tab anymore (see the field removal above) — cached here so
        // serverFromFields() can carry them through unchanged instead of silently blanking them out.
        currentManagerBaseUrl = server.managerBaseUrl() == null ? "" : server.managerBaseUrl();
        currentManagerApiKey = server.managerApiKey() == null ? "" : server.managerApiKey();

        // Server Management tab's mirror of the same fields — see the field declarations' javadoc.
        // Same hiding treatment: a profile imported from an encrypted connection file is meant to be
        // connected to, not hosted/administered, but hide it here too rather than leaving a backdoor.
        applyMaskedOrRealText(apiUrlField2, server.apiBaseUrl(), hidden);
        applyMaskedOrRealText(gameAddressField2, server.serverAddress(), hidden);
        applyMaskedOrRealText(apiKeyField2, server.apiKey() == null ? "" : server.apiKey(), hidden);
        apiUrlRevealButton2.setDisable(hidden);
        gameAddressRevealButton2.setDisable(hidden);
        mcVersionField2.setText(server.minecraftVersion());
        loaderVersionField2.setText(server.fabricLoaderVersion());
        managerUrlField2.setText(server.managerBaseUrl() == null ? "" : server.managerBaseUrl());
        managerApiKeyField2.setText(server.managerApiKey() == null ? "" : server.managerApiKey());

        currentServerShown = server;
        refreshCurrentServerLabel();
    }

    /**
     * Either shows {@code realValue} normally, or — for a hidden-details profile (see
     * hiddenDetailProfileNames) — a fixed placeholder in a non-editable field, so the real value
     * never renders anywhere in the UI. serverFromFields()/serverFromFields2() read the real value
     * back from currentServerShown (not from the field) when hidden, so this is display-only.
     */
    private void applyMaskedOrRealText(TextField field, String realValue, boolean hidden) {
        field.setText(hidden ? Messages.get("value.connectionDetailsHidden") : realValue);
        field.setEditable(!hidden);
    }

    private ServerProfile serverFromFields() {
        String name = serverBox.getEditor().getText();
        boolean hidden = hiddenDetailProfileNames.contains(currentServerShown.name());
        return new ServerProfile(
                name == null || name.isBlank() ? "Custom" : name.trim(),
                hidden ? currentServerShown.apiBaseUrl() : apiUrlField.getText().trim(),
                hidden ? currentServerShown.serverAddress() : gameAddressField.getText().trim(),
                mcVersionField.getText().trim(),
                loaderVersionField.getText().trim(),
                hidden ? currentServerShown.apiKey() : apiKeyField.getText().trim(),
                currentManagerBaseUrl,
                currentManagerApiKey);
    }

    /** Same shape as {@link #serverFromFields()} but reading the Server Management tab's own fields/profile picker. */
    private ServerProfile serverFromFields2() {
        ServerProfile selected = serverBox2.getValue();
        String name = selected != null ? selected.name() : serverBox.getEditor().getText();
        boolean hidden = hiddenDetailProfileNames.contains(currentServerShown.name());
        return new ServerProfile(
                name == null || name.isBlank() ? "Custom" : name.trim(),
                hidden ? currentServerShown.apiBaseUrl() : apiUrlField2.getText().trim(),
                hidden ? currentServerShown.serverAddress() : gameAddressField2.getText().trim(),
                mcVersionField2.getText().trim(),
                loaderVersionField2.getText().trim(),
                hidden ? currentServerShown.apiKey() : apiKeyField2.getText().trim(),
                managerUrlField2.getText().trim(),
                managerApiKeyField2.getText().trim());
    }

    /** Small modal PasswordField prompt for {@link ConnectionFileCrypto} export/import — empty Optional if cancelled or left blank. */
    private Optional<String> showPasswordPromptDialog(String titleKey) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(Messages.get(titleKey));
        if (stage != null) {
            dialog.initOwner(stage);
        }
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(Messages.get("prompt.connectionFilePassword"));
        VBox content = new VBox(10, new Label(Messages.get("label.connectionFilePassword")), passwordField);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? passwordField.getText() : null);
        return dialog.showAndWait().filter(password -> password != null && !password.isBlank());
    }

    private void showSimpleAlert(Alert.AlertType type, String titleKey, String content) {
        Alert alert = new Alert(type, content, ButtonType.OK);
        alert.setTitle(Messages.get(titleKey));
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    /** Prompts for a new name for the selected launch profile, keeping its player name/memory settings. */
    private void showRenameLaunchProfileDialog() {
        LaunchProfile selected = launchProfileBox.getValue();
        if (selected == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(selected.name());
        dialog.setTitle(Messages.get("dialog.renameLaunchProfile.title"));
        dialog.setHeaderText(null);
        dialog.setContentText(Messages.get("label.launchProfile"));
        if (stage != null) {
            dialog.initOwner(stage);
        }
        dialog.showAndWait()
                .map(String::trim)
                .filter(newName -> !newName.isBlank() && !newName.equals(selected.name()))
                .ifPresent(newName -> onRenameLaunchProfileClick.accept(selected.name(),
                        new LaunchProfile(newName, selected.playerName(), selected.minMemoryMb(), selected.maxMemoryMb())));
    }

    /**
     * Explicit "add a new launch profile" flow — same reasoning as showAddProfileDialog: a small
     * standalone dialog rather than editable-in-place, so it's obvious this creates a new entry.
     * Player name defaults to whatever's currently in the Play tab's field, since a new profile for
     * the same person (just different memory settings) is the common case.
     */
    private void showAddLaunchProfileDialog() {
        Dialog<LaunchProfile> dialog = new Dialog<>();
        dialog.setTitle(Messages.get("dialog.addLaunchProfile.title"));
        if (stage != null) {
            dialog.initOwner(stage);
        }

        TextField nameField = new TextField();
        TextField newPlayerNameField = new TextField(playerNameField.getText());
        Spinner<Integer> newMinMemorySpinner = new Spinner<>(256, 65536, 1024, 256);
        Spinner<Integer> newMaxMemorySpinner = new Spinner<>(256, 65536, 4096, 256);
        newMinMemorySpinner.setEditable(true);
        newMaxMemorySpinner.setEditable(true);
        commitSpinnerOnFocusLoss(newMinMemorySpinner);
        commitSpinnerOnFocusLoss(newMaxMemorySpinner);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.addRow(0, new Label(Messages.get("label.launchProfile")), nameField);
        grid.addRow(1, new Label(Messages.get("label.playerName")), newPlayerNameField);
        grid.addRow(2, new Label(Messages.get("label.minMemory")), newMinMemorySpinner);
        grid.addRow(3, new Label(Messages.get("label.maxMemory")), newMaxMemorySpinner);
        grid.setPadding(new Insets(10));
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(newPlayerNameField, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String name = nameField.getText().trim();
            return new LaunchProfile(name.isBlank() ? "Custom" : name, newPlayerNameField.getText().trim(),
                    newMinMemorySpinner.getValue(), newMaxMemorySpinner.getValue());
        });

        dialog.showAndWait().ifPresent(newProfile -> {
            onSaveLaunchProfileClick.accept(newProfile);
            launchProfileBox.getItems().removeIf(p -> p.name().equals(newProfile.name()));
            launchProfileBox.getItems().add(newProfile);
            selectLaunchProfileEverywhere(newProfile);
        });
    }

    /**
     * Explicit "add a new server profile" flow — a small standalone dialog (not the always-
     * visible Server tab form) so it's obvious this creates a new entry rather than editing the
     * current selection, and so its own API-key field can reasonably offer a Generate button
     * (useful when the new profile is for a server you're about to host yourself; see the field
     * declarations' javadoc for why the always-visible Server tab form doesn't have one).
     */
    private void showAddProfileDialog() {
        Dialog<ServerProfile> dialog = new Dialog<>();
        dialog.setTitle(Messages.get("dialog.addProfile.title"));
        if (stage != null) {
            dialog.initOwner(stage);
        }

        TextField nameField = new TextField();
        TextField newApiUrlField = new TextField(ServerProfile.DEFAULT.apiBaseUrl());
        TextField newGameAddressField = new TextField(ServerProfile.DEFAULT.serverAddress());
        TextField newMcVersionField = new TextField(ServerProfile.DEFAULT.minecraftVersion());
        TextField newLoaderVersionField = new TextField(ServerProfile.DEFAULT.fabricLoaderVersion());
        TextField newApiKeyField = new TextField();
        Button newGenerateApiKeyButton = new Button(Messages.get("button.generate"));
        newGenerateApiKeyButton.setOnAction(e -> newApiKeyField.setText(generateApiKey()));
        Button fetchGameAddressButton = new Button(Messages.get("button.fetchGameAddress"));
        fetchGameAddressButton.setOnAction(e -> {
            fetchGameAddressButton.setDisable(true);
            onFetchPublicGameAddress.apply(newApiUrlField.getText().trim(), newApiKeyField.getText().trim())
                    .whenComplete((address, error) -> {
                        fetchGameAddressButton.setDisable(false);
                        if (error != null) {
                            Alert alert = new Alert(Alert.AlertType.ERROR,
                                    Messages.get("alert.fetchGameAddress.error.content", ErrorMessages.describe(error)),
                                    ButtonType.OK);
                            alert.setTitle(Messages.get("alert.fetchGameAddress.error.title"));
                            alert.setHeaderText(null);
                            alert.showAndWait();
                        } else if (address == null || address.isBlank()) {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                                    Messages.get("alert.fetchGameAddress.notPublished.content"), ButtonType.OK);
                            alert.setTitle(Messages.get("alert.fetchGameAddress.notPublished.title"));
                            alert.setHeaderText(null);
                            alert.showAndWait();
                        } else {
                            newGameAddressField.setText(address);
                        }
                    });
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.addRow(0, new Label(Messages.get("label.server")), nameField);
        grid.addRow(1, new Label(Messages.get("label.apiUrl")), newApiUrlField);
        grid.addRow(2, new Label(Messages.get("label.gameAddress")), new HBox(6, newGameAddressField, fetchGameAddressButton));
        grid.addRow(3, new Label(Messages.get("label.mcVersion")), newMcVersionField);
        grid.addRow(4, new Label(Messages.get("label.loaderVersion")), newLoaderVersionField);
        grid.addRow(5, new Label(Messages.get("label.apiKey")), new HBox(6, newApiKeyField, newGenerateApiKeyButton));
        grid.setPadding(new Insets(10));
        for (TextField field : List.of(newApiUrlField, newGameAddressField, newMcVersionField,
                newLoaderVersionField, newApiKeyField)) {
            GridPane.setHgrow(field, Priority.ALWAYS);
        }

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String name = nameField.getText().trim();
            // Manager URL/key aren't collected here — connecting never needs them; if this profile
            // later becomes something this PC hosts, they're set up in the Server Manager tab, which
            // starts new profiles off with server-manager's documented default port and no key.
            return new ServerProfile(
                    name.isBlank() ? "Custom" : name,
                    newApiUrlField.getText().trim(),
                    newGameAddressField.getText().trim(),
                    newMcVersionField.getText().trim(),
                    newLoaderVersionField.getText().trim(),
                    newApiKeyField.getText().trim(),
                    ServerProfile.DEFAULT.managerBaseUrl(),
                    "");
        });

        dialog.showAndWait().ifPresent(newProfile -> {
            onSavePresetClick.accept(newProfile);
            serverBox.getItems().removeIf(s -> s.name().equals(newProfile.name()));
            serverBox.getItems().add(newProfile);
            serverBox.setValue(newProfile);
            serverBox2.setValue(newProfile);
            populateServerFields(newProfile);
        });
    }

    /**
     * Wires a maskable text field pair: {@code maskField} (a PasswordField, shown by default) is
     * bidirectionally text-bound to {@code textField} (the real control everything else in this
     * class reads/writes via getText()), with {@code revealButton} toggling which of the two is
     * visible — see apiUrlField/gameAddressField's declarations for why these two are masked.
     */
    private void wireMaskable(TextField textField, PasswordField maskField, Button revealButton) {
        maskField.textProperty().bindBidirectional(textField.textProperty());
        textField.setVisible(false);
        textField.setManaged(false);
        revealButton.setText(Messages.get("button.revealValue"));
        revealButton.setOnAction(e -> {
            boolean reveal = !textField.isVisible();
            textField.setVisible(reveal);
            textField.setManaged(reveal);
            maskField.setVisible(!reveal);
            maskField.setManaged(!reveal);
            revealButton.setText(Messages.get(reveal ? "button.hideValue" : "button.revealValue"));
        });
    }

    /** Lays {@code textField}/{@code maskField} (see {@link #wireMaskable}) and {@code revealButton} out in one row. */
    private static HBox maskableRow(TextField textField, PasswordField maskField, Button revealButton) {
        StackPane stack = new StackPane(maskField, textField);
        HBox.setHgrow(stack, Priority.ALWAYS);
        HBox row = new HBox(6, stack, revealButton);
        HBox.setHgrow(row, Priority.ALWAYS);
        return row;
    }

    /** See the setEditable(true) call site above for why this exists. */
    private static void commitSpinnerOnFocusLoss(Spinner<Integer> spinner) {
        spinner.getEditor().focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                try {
                    spinner.getValueFactory().setValue(Integer.parseInt(spinner.getEditor().getText().trim()));
                } catch (NumberFormatException e) {
                    spinner.getEditor().setText(String.valueOf(spinner.getValueFactory().getValue()));
                }
            }
        });
    }

    /** 24 random bytes, URL-safe base64 — long enough to not guess, short enough to paste into a command line. */
    private static String generateApiKey() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ServerSettings settingsFromFields() {
        return new ServerSettings(
                difficultyBox.getValue(),
                gamemodeBox.getValue(),
                motdField.getText(),
                maxPlayersSpinner.getValue(),
                whitelistCheckBox.isSelected(),
                hardcoreCheckBox.isSelected(),
                viewDistanceSpinner.getValue(),
                simulationDistanceSpinner.getValue(),
                spawnProtectionSpinner.getValue(),
                allowFlightCheckBox.isSelected());
    }

    private BackupSettings backupSettingsFromFields() {
        return new BackupSettings(
                retentionCountSpinner.getValue(),
                backupOnLoginCheckBox.isSelected(),
                backupOnLogoutCheckBox.isSelected(),
                backupPeriodicCheckBox.isSelected(),
                periodicIntervalSpinner.getValue(),
                cooldownSpinner.getValue(),
                allowParticipantBackupCheckBox.isSelected());
    }

    /** Copies the enabled mods' file names (one per line) to the system clipboard, for pasting into Discord/etc. */
    private void copyModsListToClipboard() {
        String text = modsItems.stream()
                .filter(ManagedFile::isEnabled)
                .map(ManagedFile::getFileName)
                .collect(Collectors.joining(System.lineSeparator()));
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        log(Messages.get("log.modsListCopied", modsItems.stream().filter(ManagedFile::isEnabled).count()));
    }

    /** Same as {@link #copyModsListToClipboard()} but for resource packs. */
    private void copyResourcePacksListToClipboard() {
        String text = resourcePacksItems.stream()
                .filter(ManagedFile::isEnabled)
                .map(ManagedFile::getFileName)
                .collect(Collectors.joining(System.lineSeparator()));
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        log(Messages.get("log.resourcePacksListCopied", resourcePacksItems.stream().filter(ManagedFile::isEnabled).count()));
    }

    private ListCell<ManagedFile> managedFileCell(boolean showCategory) {
        return new ListCell<>() {
            @Override
            protected void updateItem(ManagedFile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : managedFileLabel(item, showCategory));
            }
        };
    }

    /**
     * Same rendering as {@link #managedFileCell} plus a leading CheckBox marking the row for the
     * bulk enable/disable buttons — independent of the ListView's own (single-item) selection model,
     * which still drives Remove/Enable-Disable. {@code checkedNames} is shared mutable state (see
     * checkedModNames/checkedResourcePackNames) so it survives a modsItems/resourcePacksItems refresh.
     */
    private ListCell<ManagedFile> editableManagedFileCell(Set<String> checkedNames, boolean showCategory) {
        return new ListCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final Label label = new Label();
            private final HBox row = new HBox(8, checkBox, label);
            {
                row.setAlignment(Pos.CENTER_LEFT);
                checkBox.setOnAction(e -> {
                    ManagedFile item = getItem();
                    if (item == null) {
                        return;
                    }
                    if (checkBox.isSelected()) {
                        checkedNames.add(item.getFileName());
                    } else {
                        checkedNames.remove(item.getFileName());
                    }
                });
            }

            @Override
            protected void updateItem(ManagedFile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                checkBox.setSelected(checkedNames.contains(item.getFileName()));
                label.setText(managedFileLabel(item, showCategory));
                setGraphic(row);
            }
        };
    }

    private static String managedFileLabel(ManagedFile item, boolean showCategory) {
        String status = Messages.get(item.isEnabled() ? "value.enabled" : "value.disabled");
        if (!showCategory) {
            return Messages.get("managedFile.entry", item.getFileName(), item.getSizeBytes() / 1024, status);
        }
        ModCategory category = item.getCategory() != null ? item.getCategory() : ModCategory.BOTH;
        return Messages.get("managedFile.entry.withCategory", item.getFileName(), item.getSizeBytes() / 1024,
                status, Messages.get(categoryMessageKey(category)));
    }

    private static String categoryMessageKey(ModCategory category) {
        return switch (category) {
            case SERVER_ONLY -> "value.category.serverOnly";
            case CLIENT_ONLY -> "value.category.clientOnly";
            case BOTH -> "value.category.both";
        };
    }

    private static ModCategory effectiveCategory(ManagedFile file) {
        return file.getCategory() != null ? file.getCategory() : ModCategory.BOTH;
    }

    @SafeVarargs
    private static void selectAll(List<ManagedFile> items, Set<String> checkedNames, ListView<ManagedFile>... listViews) {
        items.forEach(item -> checkedNames.add(item.getFileName()));
        for (ListView<ManagedFile> listView : listViews) {
            listView.refresh();
        }
    }

    @SafeVarargs
    private static void selectNone(Set<String> checkedNames, ListView<ManagedFile>... listViews) {
        checkedNames.clear();
        for (ListView<ManagedFile> listView : listViews) {
            listView.refresh();
        }
    }

    /** Checked items that are currently in {@code currentEnabledState} — i.e. the ones a flip would move to the opposite. */
    private static List<ManagedFile> checkedNeedingFlip(List<ManagedFile> items, Set<String> checkedNames, boolean currentEnabledState) {
        return items.stream()
                .filter(item -> checkedNames.contains(item.getFileName()) && item.isEnabled() == currentEnabledState)
                .collect(Collectors.toList());
    }

    /**
     * Opens server/{subDir} (mods or resourcepacks) in the OS file browser. Only meaningful when this
     * PC is actually hosting the server (see hostModeCheckBox) — otherwise there's no local folder that
     * corresponds to what the admin list above is showing, so this just explains that instead of
     * guessing at (and possibly creating) an unrelated directory.
     */
    private void openLocalServerFolder(String subDir) {
        if (!hostModeCheckBox.isSelected()) {
            logModsSettings(Messages.get("mods.log.openFolderNeedsHostMode"));
            return;
        }
        String serverDirText = serverDirectoryField.getText();
        Path serverDir = (serverDirText == null || serverDirText.isBlank()) ? Path.of("..", "server") : Path.of(serverDirText);
        Path target = serverDir.resolve(subDir);
        try {
            Files.createDirectories(target);
            Desktop.getDesktop().open(target.toFile());
        } catch (Exception e) {
            logModsSettings(Messages.get("mods.log.openFolderFailed", target, e.getMessage()));
        }
    }

    /** Multi-select — returns an empty list (never null) if the dialog is cancelled. */
    private static List<Path> chooseFiles(Stage stage, String extensionPattern) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extensionPattern, extensionPattern));
        List<File> chosen = chooser.showOpenMultipleDialog(stage);
        return chosen == null ? List.of() : chosen.stream().map(File::toPath).toList();
    }

    /** Single-select — returns null if the dialog is cancelled. */
    private static Path chooseFile(Stage stage, String extensionPattern) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extensionPattern, extensionPattern));
        File chosen = chooser.showOpenDialog(stage);
        return chosen == null ? null : chosen.toPath();
    }

    private static <T> void withSelected(ListView<T> listView, Consumer<T> handler) {
        T selected = listView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            handler.accept(selected);
        }
    }

    /** Like {@link #withSelected} but across the Mod tab's three category lists — see exclusiveSelection. */
    private void withSelectedMod(Consumer<ManagedFile> handler) {
        ManagedFile selected = modsBothListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            selected = modsServerOnlyListView.getSelectionModel().getSelectedItem();
        }
        if (selected == null) {
            selected = modsClientOnlyListView.getSelectionModel().getSelectedItem();
        }
        if (selected != null) {
            handler.accept(selected);
        }
    }

    /**
     * Makes a selection in any one of {@code listViews} clear the others, so at most one row across
     * all of them is ever selected — the Mod tab's three category lists behave like a single logical
     * selection for Remove/Enable-Disable/move-to-category (see withSelectedMod) even though they're
     * separate ListViews (a JavaFX ListView only knows about its own selection).
     */
    @SafeVarargs
    private static void exclusiveSelection(ListView<ManagedFile>... listViews) {
        for (ListView<ManagedFile> listView : listViews) {
            listView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
                if (selected == null) {
                    return;
                }
                for (ListView<ManagedFile> other : listViews) {
                    if (other != listView) {
                        other.getSelectionModel().clearSelection();
                    }
                }
            });
        }
    }

    /** Re-renders every translated piece of text — call after switching {@link Messages#setLang}. */
    public void refreshTexts() {
        String titleWithVersion = Messages.get("app.title") + " v" + AppVersion.current();
        if (stage != null) {
            stage.setTitle(titleWithVersion);
        }
        titleLabel.setText(titleWithVersion);
        hostModeTitleCheckBox.setText(Messages.get("label.titlebar.hostMode"));
        launchProfileTitleLabel.setText(Messages.get("label.launchProfile"));
        connectionProfileTitleLabel.setText(Messages.get("label.titlebar.connectionProfile"));
        hostProfileTitleLabel.setText(Messages.get("label.titlebar.hostProfile"));
        refreshSyncIndicator();
        setServerConnectable(serverConnectable);
        languageLabel.setText(Messages.get("label.language"));
        installDirLabel.setText(Messages.get("label.installDir"));
        browseButton.setText(Messages.get("button.browse"));
        resetButton.setText(Messages.get("button.reset"));
        checkForUpdateButton.setText(Messages.get("button.checkForUpdate"));
        downloadUpdateButton.setText(Messages.get(pendingUpdateAssetUrl != null ? "button.updateNow" : "button.downloadUpdate"));
        clientGroupTab.setText(Messages.get("tab.clientGroup"));
        serverGroupTab.setText(Messages.get("tab.serverGroup"));
        playTab.setText(Messages.get("tab.play"));
        serverTab.setText(Messages.get("tab.server"));
        serverPresetLabel.setText(Messages.get("label.server"));
        renameProfileButton.setText(Messages.get("button.renameProfile"));
        deleteProfileButton.setText(Messages.get("button.deleteProfile"));
        importProfileButton.setText(Messages.get("button.importProfile"));
        exportProfileButton.setText(Messages.get("button.exportProfile"));
        apiUrlLabel.setText(Messages.get("label.apiUrl"));
        apiUrlRevealButton.setText(Messages.get(apiUrlField.isVisible() ? "button.hideValue" : "button.revealValue"));
        gameAddressLabel.setText(Messages.get("label.gameAddress"));
        gameAddressRevealButton.setText(Messages.get(gameAddressField.isVisible() ? "button.hideValue" : "button.revealValue"));
        fetchGameAddressButton.setText(Messages.get("button.fetchGameAddress"));
        mcVersionLabel.setText(Messages.get("label.mcVersion"));
        loaderVersionLabel.setText(Messages.get("label.loaderVersion"));
        apiKeyLabel.setText(Messages.get("label.apiKey"));
        connectButton.setText(Messages.get("button.connect"));
        savePresetButton.setText(Messages.get("button.savePreset"));
        addProfileButton.setText(Messages.get("button.addProfile"));
        hostProfileLabel.setText(Messages.get("label.server"));
        hostSettingsLabel.setText(Messages.get("label.hostSettings"));
        apiUrlLabel2.setText(Messages.get("label.apiUrl"));
        apiUrlRevealButton2.setText(Messages.get(apiUrlField2.isVisible() ? "button.hideValue" : "button.revealValue"));
        gameAddressLabel2.setText(Messages.get("label.gameAddress"));
        gameAddressRevealButton2.setText(Messages.get(gameAddressField2.isVisible() ? "button.hideValue" : "button.revealValue"));
        mcVersionLabel2.setText(Messages.get("label.mcVersion"));
        loaderVersionLabel2.setText(Messages.get("label.loaderVersion"));
        apiKeyLabel2.setText(Messages.get("label.apiKey"));
        generateApiKeyButton.setText(Messages.get("button.generate"));
        managerUrlLabel2.setText(Messages.get("label.managerUrl"));
        managerUrlRevealButton2.setText(Messages.get(managerUrlField2.isVisible() ? "button.hideValue" : "button.revealValue"));
        managerApiKeyLabel2.setText(Messages.get("label.managerApiKey"));
        generateManagerApiKeyButton.setText(Messages.get("button.generate"));
        saveHostSettingsButton.setText(Messages.get("button.saveHostSettings"));
        exportConnectionFileButton.setText(Messages.get("button.exportConnectionFile"));
        exportConnectionFileEncryptedButton.setText(Messages.get("button.exportConnectionFileEncrypted"));
        refreshCurrentServerLabel();
        playLogLabel.setText(Messages.get("label.log"));
        playLogCollapseButton.setText(Messages.get(logArea.isVisible() ? "button.log.collapse" : "button.log.expand"));
        playLogClearButton.setText(Messages.get("button.log.clear"));
        serverLogLabel.setText(Messages.get("label.log"));
        serverLogCollapseButton.setText(Messages.get(serverLogArea.isVisible() ? "button.log.collapse" : "button.log.expand"));
        serverLogClearButton.setText(Messages.get("button.log.clear"));
        serverManagerTab.setText(Messages.get("tab.serverManager"));
        playerTab.setText(Messages.get("tab.player"));
        backupTab.setText(Messages.get("tab.backup"));
        hostModeCheckBox.setText(Messages.get("label.hostMode"));
        serverDirectoryLabel.setText(Messages.get("label.serverDirectory"));
        serverDirectoryBrowseButton.setText(Messages.get("button.browse"));
        portForwardingCheckBox.setText(Messages.get("label.portForwarding"));
        portForwardingStatusLabel.setText(Messages.get("label.portForwarding.status"));
        copyExternalIpButton.setText(Messages.get("button.portForwarding.copyIp"));
        managerStateLabel.setText(Messages.get("label.manager.state"));
        managerUptimeLabel.setText(Messages.get("label.manager.uptime"));
        managerPidLabel.setText(Messages.get("label.manager.pid"));
        managerLastBackupLabel.setText(Messages.get("label.manager.lastBackup"));
        managerCrashCountLabel.setText(Messages.get("label.manager.crashCount"));
        backupsListLabel.setText(Messages.get("label.manager.backups"));
        backupSettingsLabel.setText(Messages.get("label.backup.settings"));
        retentionCountLabel.setText(Messages.get("label.backup.retentionCount"));
        backupOnLoginCheckBox.setText(Messages.get("label.backup.onLogin"));
        backupOnLogoutCheckBox.setText(Messages.get("label.backup.onLogout"));
        cooldownLabel.setText(Messages.get("label.backup.cooldown"));
        backupPeriodicCheckBox.setText(Messages.get("label.backup.periodic"));
        periodicIntervalLabel.setText(Messages.get("label.backup.periodicInterval"));
        allowParticipantBackupCheckBox.setText(Messages.get("label.backup.allowParticipant"));
        saveBackupSettingsButton.setText(Messages.get("button.backup.save"));
        managerRefreshButton.setText(Messages.get("button.manager.refresh"));
        managerStartRestartButton.setText(Messages.get("button.manager.startRestart"));
        managerStopButton.setText(Messages.get("button.manager.stop"));
        managerBackupNowButton.setText(Messages.get("button.manager.backupNow"));
        launchProfileLabel.setText(Messages.get("label.launchProfile"));
        addLaunchProfileButton.setText(Messages.get("button.addProfile"));
        renameLaunchProfileButton.setText(Messages.get("button.renameProfile"));
        importLaunchProfileButton.setText(Messages.get("button.importProfile"));
        exportLaunchProfileButton.setText(Messages.get("button.exportProfile"));
        deleteLaunchProfileButton.setText(Messages.get("button.deleteProfile"));
        accountHeaderLabel.setText(Messages.get("label.account"));
        applyAccountStatusText();
        playerNameLabel.setText(Messages.get("label.playerName"));
        playerNameField.setPromptText(Messages.get("prompt.playerName"));
        changeSkinButton.setText(Messages.get("button.changeSkin"));
        msaClientIdLabel.setText(Messages.get("label.msaClientId"));
        signInButton.setText(Messages.get("button.signInMicrosoft"));
        signOutButton.setText(Messages.get("button.signOutMicrosoft"));
        msaOpenBrowserButton.setText(Messages.get("button.openBrowser"));
        msaCancelButton.setText(Messages.get("button.cancelSignIn"));
        playModsLabel.setText(Messages.get("label.mods.status"));
        copyModsListButton.setText(Messages.get("button.copyModsList"));
        playResourcePacksLabel.setText(Messages.get("label.resourcePacks.status"));
        copyResourcePacksListButton.setText(Messages.get("button.copyResourcePacksList"));
        updateButton.setText(Messages.get("button.update"));
        playButton.setText(Messages.get("button.play"));
        closeButton.setText(Messages.get("button.close"));
        statusLabel.setText(Messages.get(statusKey, statusArgs));
        if (pendingUpdateVersion != null) {
            launcherUpdateLabel.setText(Messages.get("notice.updateAvailable", pendingUpdateVersion));
        }

        onlinePlayersLabel.setText(Messages.get("label.manager.onlinePlayers"));
        scheduledRestartLabel.setText(Messages.get("label.manager.scheduledRestart"));
        cancelScheduledRestartButton.setText(Messages.get("button.manager.cancelScheduledRestart"));
        managerLogLabel.setText(Messages.get("label.log"));
        managerLogCollapseButton.setText(Messages.get(managerLogArea.isVisible() ? "button.log.collapse" : "button.log.expand"));
        managerLogClearButton.setText(Messages.get("button.log.clear"));

        whitelistLabel.setText(Messages.get("label.manager.whitelist"));
        whitelistNameField.setPromptText(Messages.get("label.manager.whitelistNamePrompt"));
        addWhitelistButton.setText(Messages.get("button.add"));
        removeWhitelistButton.setText(Messages.get("button.remove"));
        connectionAttemptsLabel.setText(Messages.get("label.manager.connectionAttempts"));
        addAttemptToWhitelistButton.setText(Messages.get("button.manager.addAttemptToWhitelist"));

        worldsTab.setText(Messages.get("tab.worlds"));
        worldsLabel.setText(Messages.get("tab.worlds"));
        newWorldNameField.setPromptText(Messages.get("label.newWorldName"));
        cloneActiveWorldCheckBox.setText(Messages.get("checkbox.cloneActiveWorld"));
        createWorldButton.setText(Messages.get("button.createWorld"));
        activateWorldButton.setText(Messages.get("button.activateWorld"));
        renameWorldButton.setText(Messages.get("button.renameWorld"));
        deleteWorldButton.setText(Messages.get("button.deleteWorld"));

        modTab.setText(Messages.get("tab.mod"));
        resourcePacksTab.setText(Messages.get("tab.resourcePacks"));
        settingsTab.setText(Messages.get("tab.settings"));
        preventOverwriteCheckBox.setText(Messages.get("label.preventOverwriteOnUpload"));
        resourcePacksPreventOverwriteCheckBox.setText(Messages.get("label.preventOverwriteOnUpload"));
        publicGameAddressLabel.setText(Messages.get("label.publicGameAddress"));
        savePublicGameAddressButton.setText(Messages.get("button.publicGameAddress.save"));
        modsBothLabel.setText(Messages.get("value.category.both"));
        modsServerOnlyLabel.setText(Messages.get("value.category.serverOnly"));
        modsClientOnlyLabel.setText(Messages.get("value.category.clientOnly"));
        addModButton.setText(Messages.get("button.add"));
        removeModButton.setText(Messages.get("button.remove"));
        toggleModButton.setText(Messages.get("button.toggleEnabled"));
        openModsFolderButton.setText(Messages.get("button.openModsFolder"));
        moveModToBothButton.setText(Messages.get("button.moveToBoth"));
        moveModToServerOnlyButton.setText(Messages.get("button.moveToServerOnly"));
        moveModToClientOnlyButton.setText(Messages.get("button.moveToClientOnly"));
        selectAllModsButton.setText(Messages.get("button.selectAll"));
        selectNoneModsButton.setText(Messages.get("button.selectNone"));
        enableSelectedModsButton.setText(Messages.get("button.enableSelected"));
        disableSelectedModsButton.setText(Messages.get("button.disableSelected"));
        resourcePacksListLabel.setText(Messages.get("label.resourcePacks"));
        addResourcePackButton.setText(Messages.get("button.add"));
        removeResourcePackButton.setText(Messages.get("button.remove"));
        toggleResourcePackButton.setText(Messages.get("button.toggleEnabled"));
        openResourcePacksFolderButton.setText(Messages.get("button.openResourcePacksFolder"));
        selectAllResourcePacksButton.setText(Messages.get("button.selectAll"));
        selectNoneResourcePacksButton.setText(Messages.get("button.selectNone"));
        enableSelectedResourcePacksButton.setText(Messages.get("button.enableSelected"));
        disableSelectedResourcePacksButton.setText(Messages.get("button.disableSelected"));
        resourcePacksLogLabel.setText(Messages.get("label.log"));
        resourcePacksLogCollapseButton.setText(Messages.get(resourcePacksLogArea.isVisible() ? "button.log.collapse" : "button.log.expand"));
        resourcePacksLogClearButton.setText(Messages.get("button.log.clear"));
        settingsLabel.setText(Messages.get("label.settings"));
        difficultyLabel.setText(Messages.get("label.settings.difficulty"));
        gamemodeLabel.setText(Messages.get("label.settings.gamemode"));
        motdLabel.setText(Messages.get("label.settings.motd"));
        maxPlayersLabel.setText(Messages.get("label.settings.maxPlayers"));
        viewDistanceLabel.setText(Messages.get("label.settings.viewDistance"));
        simulationDistanceLabel.setText(Messages.get("label.settings.simulationDistance"));
        spawnProtectionLabel.setText(Messages.get("label.settings.spawnProtection"));
        whitelistCheckBox.setText(Messages.get("label.settings.whitelist"));
        hardcoreCheckBox.setText(Messages.get("label.settings.hardcore"));
        allowFlightCheckBox.setText(Messages.get("label.settings.allowFlight"));
        loadSettingsButton.setText(Messages.get("button.settings.load"));
        saveSettingsButton.setText(Messages.get("button.settings.save"));
        settingsNoteLabel.setText(Messages.get("label.settings.note"));
        pendingChangesLabel.setText(Messages.get("label.pendingChanges"));
        restartNowButton.setText(Messages.get("button.restartNow"));
        scheduleRestartButton.setText(Messages.get("button.scheduleRestart"));
        dismissPendingChangesButton.setText(Messages.get("button.dismiss"));
        modsSettingsLogLabel.setText(Messages.get("label.log"));
        modsSettingsLogCollapseButton.setText(Messages.get(modsSettingsLogArea.isVisible() ? "button.log.collapse" : "button.log.expand"));
        modsSettingsLogClearButton.setText(Messages.get("button.log.clear"));
    }

    public void setStatus(String key, Object... args) {
        this.statusKey = key;
        this.statusArgs = args;
        statusLabel.setText(Messages.get(key, args));
    }

    /** For transient text already localized by the caller (e.g. progress ticks) — not remembered across a language switch. */
    public void setStatusRaw(String text) {
        statusLabel.setText(text);
    }

    public void setProgress(double fraction) {
        progressBar.setProgress(fraction);
    }

    /** Play tab's log — install dir/sync/update/play messages. */
    public void log(String line) {
        logArea.appendText(line + System.lineSeparator());
    }

    /** Server tab's log — connect/save-preset messages. */
    public void logServer(String line) {
        serverLogArea.appendText(line + System.lineSeparator());
    }

    /** Server Manager tab's log — start/stop/backup/host-mode messages. */
    public void logManager(String line) {
        managerLogArea.appendText(line + System.lineSeparator());
    }

    /** Mod tab's log — mod admin/server settings messages. */
    public void logModsSettings(String line) {
        modsSettingsLogArea.appendText(line + System.lineSeparator());
    }

    /** Resource Pack tab's log — resource pack admin messages. */
    public void logResourcePacks(String line) {
        resourcePacksLogArea.appendText(line + System.lineSeparator());
    }

    public void setUpdateButtonVisible(boolean visible) {
        updateButton.setVisible(visible);
        updateButton.setManaged(visible);
        updateButtonVisible = visible;
        refreshSyncIndicator();
    }

    public void setPlayButtonVisible(boolean visible) {
        playButton.setVisible(visible);
        playButton.setManaged(visible);
        playButtonVisible = visible;
        refreshSyncIndicator();
    }

    /**
     * Title-bar "is the install directory's launch configuration current"
     * indicator — derived from the same update/play button visibility the
     * rest of the Play tab already uses, not a separate check. Both false
     * (mid checkForUpdates(), before either button is shown) reads as
     * "checking", not "up to date" — updateButtonVisible=true always wins
     * regardless of playButtonVisible's value.
     */
    private void refreshSyncIndicator() {
        if (updateButtonVisible) {
            syncStatusIndicator.setText(Messages.get("label.titlebar.sync.updateNeeded"));
            syncStatusIndicator.setStyle(STATUS_WARN_STYLE);
        } else if (playButtonVisible) {
            syncStatusIndicator.setText(Messages.get("label.titlebar.sync.upToDate"));
            syncStatusIndicator.setStyle(STATUS_OK_STYLE);
        } else {
            syncStatusIndicator.setText(Messages.get("label.titlebar.sync.checking"));
            syncStatusIndicator.setStyle(STATUS_NEUTRAL_STYLE);
        }
    }

    /**
     * Title-bar "can the active server actually be joined right now"
     * indicator. Reflects server-manager's own view (ManagerStatus.state),
     * refreshed automatically by LauncherApp's ~5s background poll — see
     * setServerConnectable(false) for the "server-manager unreachable at
     * all" case, which showManagerStatus() never gets called for.
     */
    public void setServerConnectable(boolean connectable) {
        this.serverConnectable = connectable;
        serverConnectableIndicator.setText(Messages.get(
                connectable ? "label.titlebar.server.connectable" : "label.titlebar.server.unreachable"));
        serverConnectableIndicator.setStyle(connectable ? STATUS_OK_STYLE : STATUS_WARN_STYLE);
    }

    public void setControlsDisabled(boolean disabled) {
        updateButton.setDisable(disabled);
        playButton.setDisable(disabled);
        launchProfileBox.setDisable(disabled);
        launchProfileTitleBox.setDisable(disabled);
        connectionProfileTitleBox.setDisable(disabled);
        hostProfileTitleBox.setDisable(disabled);
        serverBox.setDisable(disabled);
        apiUrlField.setDisable(disabled);
        apiUrlMaskField.setDisable(disabled);
        apiUrlRevealButton.setDisable(disabled);
        gameAddressField.setDisable(disabled);
        gameAddressMaskField.setDisable(disabled);
        gameAddressRevealButton.setDisable(disabled);
        fetchGameAddressButton.setDisable(disabled);
        mcVersionField.setDisable(disabled);
        loaderVersionField.setDisable(disabled);
        apiKeyField.setDisable(disabled);
        connectButton.setDisable(disabled);
        savePresetButton.setDisable(disabled);
        addProfileButton.setDisable(disabled);
        renameProfileButton.setDisable(disabled);
        deleteProfileButton.setDisable(disabled);
        importProfileButton.setDisable(disabled);
        exportProfileButton.setDisable(disabled);
        addLaunchProfileButton.setDisable(disabled);
        renameLaunchProfileButton.setDisable(disabled);
        deleteLaunchProfileButton.setDisable(disabled);
        importLaunchProfileButton.setDisable(disabled);
        exportLaunchProfileButton.setDisable(disabled);
        installDirField.setDisable(disabled);
        browseButton.setDisable(disabled);
        resetButton.setDisable(disabled);
        checkForUpdateButton.setDisable(disabled);
        playerNameField.setDisable(disabled);
        changeSkinButton.setDisable(disabled);
        msaClientIdField.setDisable(disabled);
        signInButton.setDisable(disabled);
        signOutButton.setDisable(disabled);
    }

    /**
     * Displays a fetched ManagerStatus — state string shown as-is (untranslated, forward-compat
     * per its javadoc). No longer touches setServerConnectable: that now reflects a plain TCP
     * check against the game address from the connecting side (see LauncherApp's
     * checkGameServerConnectable), not server-manager's own admin-API view of its supervised
     * process — a joining player normally has no manager key at all, so tying "can I join" to
     * whether the *manager* is reachable made it read as permanently unreachable for them.
     */
    public void showManagerStatus(ManagerStatus status) {
        managerStateValue.setText(status.getState());
        managerUptimeValue.setText(formatUptime(status.getUptimeMillis()));
        managerPidValue.setText(status.getPid() >= 0 ? String.valueOf(status.getPid()) : Messages.get("value.notApplicable"));
        managerLastBackupValue.setText(status.getLastBackupAtMillis() > 0
                ? Instant.ofEpochMilli(status.getLastBackupAtMillis()).toString()
                : Messages.get("value.never"));
        managerCrashCountValue.setText(String.valueOf(status.getCrashRestartCount()));
        onlinePlayersListView.getItems().setAll(status.getOnlinePlayers() == null ? List.of() : status.getOnlinePlayers());

        boolean pending = status.getPendingRestartAtMillis() > 0;
        if (pending) {
            long remainingSeconds = Math.max(0, (status.getPendingRestartAtMillis() - System.currentTimeMillis()) / 1000);
            scheduledRestartValue.setText(Messages.get("value.scheduledRestart.pending", remainingSeconds / 60, remainingSeconds % 60));
        } else {
            scheduledRestartValue.setText(Messages.get("value.scheduledRestart.none"));
        }
        cancelScheduledRestartButton.setVisible(pending);
        cancelScheduledRestartButton.setManaged(pending);
    }

    public void showBackups(List<BackupInfo> backups) {
        backupsListView.getItems().setAll(backups);
    }

    public void showWhitelist(List<WhitelistEntry> entries) {
        whitelistListView.getItems().setAll(entries);
    }

    public void showWorlds(List<WorldProfile> profiles) {
        worldsListView.getItems().setAll(profiles);
    }

    /** Most recent first — see ManagerClient.listConnectionAttempts(). */
    public void showConnectionAttempts(List<ConnectionAttempt> attempts) {
        connectionAttemptsListView.getItems().setAll(attempts);
    }

    /** Displays server-manager's latest UPnP mapping attempt — see shared PortForwardingStatus. */
    public void showPortForwardingStatus(PortForwardingStatus status) {
        // GetExternalIPAddress is asked before AddPortMapping (see PortForwardingService), so the
        // router can hand back an IP even on a mapping failure — show the copy button whenever we
        // have one, not only on full success, since that's still useful for a manual-forward fallback.
        currentExternalIp = status.getExternalIpAddress();
        copyExternalIpButton.setVisible(currentExternalIp != null);
        copyExternalIpButton.setManaged(currentExternalIp != null);

        if (!status.isEnabled()) {
            portForwardingStatusValue.setText(Messages.get("value.portForwarding.disabled"));
            portForwardingStatusValue.setStyle(STATUS_NEUTRAL_STYLE);
            return;
        }
        if (status.getLastError() != null) {
            portForwardingStatusValue.setText(Messages.get("value.portForwarding.error", status.getLastError()));
            portForwardingStatusValue.setStyle(STATUS_WARN_STYLE);
            return;
        }
        String externalIp = currentExternalIp != null ? currentExternalIp : Messages.get("value.notApplicable");
        portForwardingStatusValue.setText(Messages.get("value.portForwarding.mapped", externalIp));
        portForwardingStatusValue.setStyle(STATUS_OK_STYLE);
    }

    /** Copies the router's external IP to the clipboard, for pasting straight into a chat to friends. */
    private void copyExternalIpToClipboard() {
        if (currentExternalIp == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(currentExternalIp);
        Clipboard.getSystemClipboard().setContent(content);
        logManager(Messages.get("manager.log.externalIpCopied", currentExternalIp));
    }

    public void setManagerControlsDisabled(boolean disabled) {
        managerRefreshButton.setDisable(disabled);
        managerStartRestartButton.setDisable(disabled);
        managerStopButton.setDisable(disabled);
        managerBackupNowButton.setDisable(disabled);
        whitelistNameField.setDisable(disabled);
        addWhitelistButton.setDisable(disabled);
        removeWhitelistButton.setDisable(disabled);
        addAttemptToWhitelistButton.setDisable(disabled);
        newWorldNameField.setDisable(disabled);
        cloneActiveWorldCheckBox.setDisable(disabled);
        createWorldButton.setDisable(disabled);
        activateWorldButton.setDisable(disabled);
        renameWorldButton.setDisable(disabled);
        deleteWorldButton.setDisable(disabled);
        publicGameAddressField.setDisable(disabled);
        savePublicGameAddressButton.setDisable(disabled);
        serverBox2.setDisable(disabled);
        apiUrlField2.setDisable(disabled);
        apiUrlMaskField2.setDisable(disabled);
        apiUrlRevealButton2.setDisable(disabled);
        gameAddressField2.setDisable(disabled);
        gameAddressMaskField2.setDisable(disabled);
        gameAddressRevealButton2.setDisable(disabled);
        mcVersionField2.setDisable(disabled);
        loaderVersionField2.setDisable(disabled);
        apiKeyField2.setDisable(disabled);
        generateApiKeyButton.setDisable(disabled);
        managerUrlField2.setDisable(disabled);
        managerUrlMaskField2.setDisable(disabled);
        managerUrlRevealButton2.setDisable(disabled);
        managerApiKeyField2.setDisable(disabled);
        generateManagerApiKeyButton.setDisable(disabled);
        saveHostSettingsButton.setDisable(disabled);
        exportConnectionFileButton.setDisable(disabled);
        exportConnectionFileEncryptedButton.setDisable(disabled);
        retentionCountSpinner.setDisable(disabled);
        backupOnLoginCheckBox.setDisable(disabled);
        backupOnLogoutCheckBox.setDisable(disabled);
        cooldownSpinner.setDisable(disabled);
        backupPeriodicCheckBox.setDisable(disabled);
        periodicIntervalSpinner.setDisable(disabled);
        allowParticipantBackupCheckBox.setDisable(disabled);
        saveBackupSettingsButton.setDisable(disabled);
    }

    /** Reflects sync-server's own admin toggle(s) — see SyncServerSettings. Doesn't fire onPreventOverwriteChange. */
    public void showSyncSettings(SyncServerSettings settings) {
        preventOverwriteCheckBox.setSelected(settings.isPreventOverwriteOnUpload());
        resourcePacksPreventOverwriteCheckBox.setSelected(settings.isPreventOverwriteOnUpload());
        publicGameAddressField.setText(settings.getPublicGameServerAddress() == null ? "" : settings.getPublicGameServerAddress());
    }

    /** Feeds both the Mod tab's three category lists and the Play tab's read-only status list — same backing list. */
    public void showMods(List<ManagedFile> mods) {
        modsItems.setAll(mods);
    }

    /** Feeds both the Resource Pack tab's list and the Play tab's read-only status list — same backing list. */
    public void showResourcePacks(List<ManagedFile> resourcePacks) {
        resourcePacksItems.setAll(resourcePacks);
    }

    public void showSettings(ServerSettings settings) {
        difficultyBox.setValue(settings.getDifficulty());
        gamemodeBox.setValue(settings.getGamemode());
        motdField.setText(settings.getMotd());
        maxPlayersSpinner.getValueFactory().setValue(settings.getMaxPlayers());
        viewDistanceSpinner.getValueFactory().setValue(settings.getViewDistance());
        simulationDistanceSpinner.getValueFactory().setValue(settings.getSimulationDistance());
        spawnProtectionSpinner.getValueFactory().setValue(settings.getSpawnProtection());
        whitelistCheckBox.setSelected(settings.isWhitelist());
        hardcoreCheckBox.setSelected(settings.isHardcore());
        allowFlightCheckBox.setSelected(settings.isAllowFlight());
    }

    public void showBackupSettings(BackupSettings settings) {
        retentionCountSpinner.getValueFactory().setValue(settings.getRetentionCount());
        backupOnLoginCheckBox.setSelected(settings.isBackupOnLogin());
        backupOnLogoutCheckBox.setSelected(settings.isBackupOnLogout());
        backupPeriodicCheckBox.setSelected(settings.isBackupPeriodic());
        periodicIntervalSpinner.getValueFactory().setValue(settings.getPeriodicIntervalMinutes());
        cooldownSpinner.getValueFactory().setValue(settings.getLoginLogoutCooldownSeconds());
        allowParticipantBackupCheckBox.setSelected(settings.isAllowParticipantTriggeredBackup());
    }

    /** Shows/hides the "changes are pending — restart to apply" banner — call after any mod/resource pack/settings change. */
    public void setPendingChangesVisible(boolean visible) {
        pendingChangesRow.setVisible(visible);
        pendingChangesRow.setManaged(visible);
    }

    public void setModsControlsDisabled(boolean disabled) {
        preventOverwriteCheckBox.setDisable(disabled);
        resourcePacksPreventOverwriteCheckBox.setDisable(disabled);
        addModButton.setDisable(disabled);
        removeModButton.setDisable(disabled);
        toggleModButton.setDisable(disabled);
        moveModToBothButton.setDisable(disabled);
        moveModToServerOnlyButton.setDisable(disabled);
        moveModToClientOnlyButton.setDisable(disabled);
        selectAllModsButton.setDisable(disabled);
        selectNoneModsButton.setDisable(disabled);
        enableSelectedModsButton.setDisable(disabled);
        disableSelectedModsButton.setDisable(disabled);
        addResourcePackButton.setDisable(disabled);
        removeResourcePackButton.setDisable(disabled);
        toggleResourcePackButton.setDisable(disabled);
        selectAllResourcePacksButton.setDisable(disabled);
        selectNoneResourcePacksButton.setDisable(disabled);
        enableSelectedResourcePacksButton.setDisable(disabled);
        disableSelectedResourcePacksButton.setDisable(disabled);
        loadSettingsButton.setDisable(disabled);
        saveSettingsButton.setDisable(disabled);
        restartNowButton.setDisable(disabled);
        scheduleRestartButton.setDisable(disabled);
    }

    /** Greys out the checkbox (with no way to check it) when not running as the packaged app — see LocalServiceLauncher.isSupported(). */
    public void setHostModeSupported(boolean supported) {
        hostModeCheckBox.setDisable(!supported);
        hostModeTitleCheckBox.setDisable(!supported);
        portForwardingCheckBox.setDisable(!supported);
    }

    /** Minimizes to the taskbar instead of closing — used when the hosted server-manager process is still running. */
    public void hideToTaskbar() {
        stage.setIconified(true);
    }

    /** For transient text already localized by the caller — not remembered across a language switch. */
    public void setManagerStatusRaw(String text) {
        managerStatusLabel.setText(text);
    }

    private static String formatUptime(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        long totalSeconds = millis / 1000;
        return String.format("%dh %02dm %02ds", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    private static <T> StringConverter<T> nameConverter(java.util.function.Function<T, String> nameOf) {
        return new StringConverter<>() {
            @Override
            public String toString(T item) {
                return item == null ? "" : nameOf.apply(item);
            }

            @Override
            public T fromString(String string) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /**
     * Every tab shares this shape: form/controls on top, the log pane
     * below it, both filling the tab's full width, with a draggable
     * divider between them (SplitPane, VERTICAL orientation) so the player
     * can resize either side to taste — the divider position isn't
     * persisted, so it resets to {@code initialDividerPosition} each launch.
     */
    private static SplitPane logSplit(Region controls, Region logPane, double initialDividerPosition) {
        controls.setMinHeight(120);
        logPane.setMinHeight(80);
        SplitPane split = new SplitPane(controls, logPane);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(initialDividerPosition);
        return split;
    }

    /**
     * Wires a log tab's collapse toggle: hides the log {@link TextArea} and
     * pushes the {@link SplitPane} divider all the way over to the controls
     * side, freeing up the space instead of leaving a blank pane. Expanding
     * restores both the text area and {@code expandedDividerPosition} (the
     * same value the tab's {@link #logSplit} call was built with).
     */
    private void wireLogCollapse(Button collapseButton, TextArea logArea, SplitPane split, double expandedDividerPosition) {
        collapseButton.setOnAction(e -> {
            boolean expanding = !logArea.isVisible();
            logArea.setVisible(expanding);
            logArea.setManaged(expanding);
            split.setDividerPositions(expanding ? expandedDividerPosition : 1.0);
            collapseButton.setText(Messages.get(expanding ? "button.log.collapse" : "button.log.expand"));
        });
    }
}
