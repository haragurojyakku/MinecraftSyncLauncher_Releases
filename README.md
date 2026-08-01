# ModServerPlayManagerByHaraguro

Minecraft **26.2** / Fabric project split into a code workspace and a
runtime deployment environment:

```
ModServerPlayManagerByHaraguro/
├── server/        runtime environment — the actual Fabric server install (not built by Gradle)
├── shared/        Gradle module — DTOs & API route constants shared by launcher/mod/sync-server
├── mod/           Gradle module — Fabric mod (client+server), talks to sync-server over HTTP
├── launcher/      Gradle module — Java launcher app: syncs mods/resourcepacks from sync-server, launches the game
├── sync-server/    Gradle module — Javalin REST API: serves server/'s mods+resourcepacks, owns player data
└── server-manager/  Gradle module — supervises the server/ process (auto-restart), takes RCON-safe backups
```

`server/` and the Gradle workspace are deliberately separate: the server is a
deployment artifact (jars you download, a world you generate, an EULA only
you can accept), while `shared`/`mod`/`launcher`/`sync-server` are source you
build. `settings.gradle.kts` only includes the latter four.

## How the pieces talk to each other

```
 mod (in-game)  --HTTP/JSON-->  sync-server  <--HTTP/JSON--  launcher
                                    |
                                    v
                     server/mods, server/resourcepacks

 server-manager  --launches/supervises-->  server/ (the Fabric process)
       |    \
       v     \--RCON (save-off/save-on, stop)--> server/
  server/backups
```

- **sync-server** scans `server/mods` and `server/resourcepacks`, hashes each file
  (SHA-1), and exposes that as a manifest.
- **launcher** fetches the manifest, downloads whatever's missing or
  changed into its local install dir. This is the "distribute the server's
  mod folder as-is" sync strategy — there's no separate mod list to
  hand-maintain, whatever is physically in `server/mods` is what gets synced.
- **mod** runs inside Minecraft (client or server) and calls sync-server
  directly for player data (`/api/players/{uuid}`) and, later, external
  services like exchange rates (`/api/external/exchange-rates`, currently a
  static placeholder). It also implements a bank/currency system built on
  emeralds — see "Bank / emerald currency" below.
- **shared** holds the DTOs (`SyncManifest` with `mods`/`resourcePacks`,
  `SyncFile`, `PlayerProfile`) and
  `Routes` path constants that `launcher` and `sync-server` both compile
  against directly. `mod` does **not** depend on `:shared`'s compiled
  classes — Fabric Loom only remaps `mod`'s own sourceSet, so a plain
  project dependency would compile but throw `NoClassDefFoundError` at
  runtime. `mod` talks JSON over HTTP instead (see `Routes` constants,
  which are compile-time constants and get inlined either way). If you
  later want `:shared`'s classes physically bundled into the mod jar too,
  add the Shadow plugin and remap the shaded jar.

## Version pins

Real, verified values as of 2026-07-25 (see `gradle.properties`) — no
guessed version numbers:

| | version |
|---|---|
| Minecraft | 26.2 (stable release) |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.155.2+26.2 |
| Fabric Loom (Gradle plugin) | 1.17.17, plugin id `net.fabricmc.fabric-loom` |
| Java | 25 (required by MC 26.2 itself) |
| Javalin | 6.7.0 (7.x's `app.get(...)` overloads didn't resolve at time of writing) |
| JavaFX | 26.0.2, via `org.openjfx.javafxplugin` 0.1.0 (launcher GUI) |
| Mappings | none — see below |

Mojang dropped obfuscation starting with the 26.1 cycle, so 26.2 ships
**unobfuscated**: there's no `client_mappings`/`server_mappings` in Mojang's
version manifest, and no Yarn/intermediary build exists for it either (both
stop at 1.21.11). Loom has a separate plugin variant for this:

- Use plugin id `net.fabricmc.fabric-loom`, **not** the classic `fabric-loom`
  (that one expects obfuscated MC + a `mappings(...)` dependency and will
  fail with "Failed to find official mojang mappings" or "Configuration
  'mappings' has no dependencies").
- No `mappings(...)` block at all — there's nothing to map.
- Use plain `implementation(...)` for Fabric Loader/API, not
  `modImplementation(...)` — remapping between intermediary and named
  mappings doesn't apply when the game already ships named.

See `mod/build.gradle.kts` and
[FabricMC/fabric-loom#1585](https://github.com/FabricMC/fabric-loom/issues/1585).

## Building

```sh
./gradlew build          # compiles shared, mod, launcher, sync-server
./gradlew :mod:build     # just the Fabric mod jar
```

## Bank / emerald currency (mod)

`mod`'s `dev.haraguro.modserverplaymanager.mod.bank` package redirects the
emerald leg of villager trades to a player's sync-server bank balance
(1 emerald = 1 currency unit) instead of physical emerald items, plus a
player-to-player transfer command:

- **`BankTradeService`** — `MerchantMenuMixin` calls `topUpEmeraldShortfall`
  before vanilla fills a trade's payment slot, withdrawing from the bank
  balance (via `ApiClient`) to cover any shortfall in physical emeralds so a
  purchase succeeds even carrying zero. `AbstractVillagerMixin` calls
  `sweepEmeraldResult` a tick after a sell-side trade settles, sweeping
  received emeralds (inventory + cursor stack) into the bank balance via
  `EmeraldUtil.remove` + `ApiClient.depositAsync`.
- **`/bank balance`** and **`/bank pay <player> <amount>`**
  (`BankCommands`) — read the caller's balance, or transfer between two
  online players, both via `ApiClient`'s async balance/transfer endpoints.
  Insufficient-balance and self-pay are rejected with a translated message.
- **Ender chest bank panel** — `EnderChestBankMixin` adds a "Bank" button to
  a player's own ender chest screen specifically (keyed off the backing
  container being a `PlayerEnderChestContainer`, so a regular chest reusing
  the same generic screen class doesn't get the button), opening
  `BankPanelScreen`. Client<->server balance/deposit/withdraw requests for
  the panel go over custom payloads in `bank/network/`
  (`BankBalanceRequestPayload`, `BankBalancePayload`,
  `BankDepositRequestPayload`, `BankWithdrawRequestPayload`,
  registered in `BankNetworking`).

Status as of 2026-08-01: implemented and building (`mod-0.6.2.jar`); rebuild
and redeploy to `server/mods/` and any local test install
(`TestAppSetting/mods/`) before relying on it in a live session, since those
copies lag behind whatever's currently in `mod/src`.

## Running sync-server + launcher locally

```sh
./gradlew :sync-server:run -Dmcsync.server.dir=../../server   # defaults to ../server
./gradlew :launcher:run
```

Both modules' `build.gradle.kts` explicitly forward specific `-D` flags
(`mcsync.*`) from the `./gradlew` command line into the forked JVM —
Gradle's `run` task does **not** do this automatically, it only sets them
on the Gradle process itself. Passing an undeclared `-Dmcsync.whatever=...`
will silently do nothing; add it to the `tasks.named<JavaExec>("run") { ... }`
block in the relevant module if you need a new one.

`sync-server` listens on `:7070` by default (`-Dmcsync.port=...` to change).
`launcher` reads `~/.mcsync/launcher-config.json` (created on first run,
migrated in place if it's from an older single-server version of this
project) for:

- `language` — `"en"`/`"ja"`/`"sv"`, or `null` to auto-detect from the OS
  locale (see "Launcher GUI" below),
- `installDir`, `playerName`,
- `activeServer` — `{name, apiBaseUrl, serverAddress, minecraftVersion,
  fabricLoaderVersion, apiKey}` for whichever server is actually in use
  right now. Freely editable, doesn't need to match anything in `servers`.
  `apiKey` is blank unless sync-server has one configured — see "Security",
- `servers` — a list of the same shape: saved presets the GUI's picker
  offers, purely for convenience. Copying one into `activeServer` (or
  hand-editing `activeServer` directly) doesn't require it to be here,
- `launchProfiles` — a list of `{name, minMemoryMb, maxMemoryMb}`,
  `selectedLaunchProfile` picks which one's active.

Add more launch profiles by hand-editing the JSON array (the GUI only
*selects* among those); `servers` entries can be added either by
hand-editing the JSON or from the GUI's Save button (see below).

The console `Launcher` (`:launcher:run`) always runs the flow against
`activeServer`/the selected launch profile. Running it does, in order:

1. syncs `mods/`+`resourcepacks/` from sync-server (see above),
2. resolves the launch profile the same way third-party launchers do for a
   Fabric install: fetches Mojang's vanilla `26.2` version JSON, fetches
   Fabric's ready-made loader profile JSON
   (`meta.fabricmc.net/v2/versions/loader/26.2/0.19.3/profile/json`), and
   merges them (Fabric's `mainClass`/extra JVM args/libraries layer on top
   of vanilla's — same `inheritsFrom` semantics real launchers use),
3. downloads the client jar + every library the merged profile lists into
   `installDir/{versions,libraries}` (skipped once already present and
   sha1-verified), and the asset index + objects into `installDir/assets`
   (can be several hundred MB on first run — set
   `-Dmcsync.downloadAssets=false` to skip this step once you already have
   an assets directory, for a faster iteration loop),
4. builds the full `java ...` command (JVM args, classpath, `KnotClient` as
   main class, game args) with all `${...}` placeholders substituted, and
   runs it, streaming the game's output straight through.

Every download phase above (mods, resourcepacks, client+libraries, assets)
reports its own `label: N/total (P%)` progress every 5%, via
`ThrottledProgressReporter` — `ConsoleProgressReporter` prints it to stdout,
`GuiProgressReporter` (see below) updates the window instead. Without this a
packaged app would just sit there looking frozen through a multi-hundred-file
asset sync.

If `serverAddress` is set in the config (default `localhost:25565` — the
Minecraft game port from `server/server.properties`, **not** `apiBaseUrl`'s
HTTP port), step 4 also adds `--quickPlayMultiplayer <address>` so the
client joins that server automatically once it boots — no manual
"Multiplayer -> Add Server" step. Set `serverAddress` to `""` to disable
auto-connect. This is exactly the same rule-gated conditional-argument
mechanism Mojang's version JSON uses for demo mode/custom resolution
(`dev.haraguro.modserverplaymanager.launcher.launch.Rules`), just with `is_quick_play_multiplayer`
turned on.

The same `serverAddress` is also written into `installDir/servers.dat` —
the game's own Multiplayer server list — right before every launch
(`ServersDatWriter`, called from `GameLauncher.launch()`). It updates the
entry whose name matches the active `ServerProfile`'s name in place (so
existing icon/hidden/acceptTextures fields on that entry survive), or
appends a new one; every other entry in the file is left untouched. This
means the server also shows up in the Multiplayer list on later runs
without quick play, e.g. after `serverAddress` is later cleared.

Put together: **launch = sync mods/resourcepacks against the server -> resolve
+ download the matching client -> auto-join that same server**, so a
player's mods are never out of date with what the server expects.

Auth is either **offline** (`dev.haraguro.modserverplaymanager.launcher.launch.OfflineAuth`
— same UUID derivation vanilla uses for cracked/LAN play; only works against
a server running `online-mode=false`) or a real **Microsoft account**
(`dev.haraguro.modserverplaymanager.launcher.auth` — device-code flow ->
Xbox Live -> XSTS -> Minecraft services token, required for
`online-mode=true` servers). See "Setting up Microsoft sign-in" below for
how to enable it. See `launcher/src/main/java/dev/haraguro/modserverplaymanager/launcher/launch/` for
the launch pipeline (`ProfileResolver`, `GameInstaller`, `LaunchCommandBuilder`,
`GameLauncher`) — it consumes whichever `AuthSession` it's handed and
doesn't care which auth method produced it.

### Setting up Microsoft sign-in (optional)

The Play tab's Account section lets players sign in with a Microsoft
account instead of typing an offline player name. This needs a one-time,
free Azure AD app registration — done once per launcher build/distribution,
not per player:

1. Go to [portal.azure.com](https://portal.azure.com) -> Azure Active
   Directory -> App registrations -> New registration.
2. Give it any name. Under "Supported account types", pick "Personal
   Microsoft accounts only". No redirect URI is needed for the device-code
   flow.
3. After creation, open Authentication and set "Allow public client flows"
   to Yes.
4. Copy the "Application (client) ID" from the Overview page.
5. Paste it into the launcher's Play tab, "Microsoft Client ID (advanced)"
   field (or `msaClientId` in `launcher-config.json`) and share it with
   whoever distributes the launcher — it's not a secret, just an
   application identifier.

Players then click "Sign in with Microsoft", get a short code + a link to
open in their browser, and approve there. The launcher caches a refresh
token (`~/.modserverplaymanager/launcher-config.json`, plaintext — like the
existing `apiKey`/`managerApiKey` fields, there's no encryption-at-rest here)
so future launches sign in silently. "Sign out" clears it.

## Launcher GUI

`dev.haraguro.modserverplaymanager.launcher.gui` (`LauncherWindow`, `LauncherApp`) is the actual
window server participants get — built for people who aren't going to read
this README. A language selector sits above three tabs:

- **Language selector, top right** (`dev.haraguro.modserverplaymanager.launcher.i18n`) — English,
  日本語, or Svenska (Sweden, because that's where Mojang/Minecraft is
  from). Defaults to whichever of the three matches the OS locale
  (`Lang.detectFromOs()`, i.e. `Locale.getDefault()`), falling back to
  English; switching it live re-renders every label immediately (not just
  on restart) and remembers the choice in `launcher-config.json`.
  Translations are a plain in-code table (`Messages`) rather than
  `.properties`/`ResourceBundle` — those default to ISO-8859-1 for property
  files unless you fight `ResourceBundle.Control`, a bad time for
  Japanese/Swedish text.

**Play tab** (default, daily use — no server-config fields here):

- **Install Directory, with a Browse... button** — a native
  `DirectoryChooser` dialog (or just type a path and press Enter). Changing
  it re-checks the current server against files in the new location.
- **Reset to Defaults** (next to Browse...) — resets install directory,
  active server, and launch profile back to their hard-coded defaults
  (`LauncherConfig.resetToDefaults()`) and deletes everything previously
  synced into `installDir/mods` and `installDir/resourcepacks`
  (`ModSyncService.cleanupSyncedFiles()`, confirmation dialog first — it's
  destructive). Meant for verifying the update flow actually works: reset,
  then Update should redetect and redownload every file from scratch.
- A **"Connected to: name (address)"** label so it's obvious which server
  is active without switching tabs.
- **Launch Profile selector** — switches `selectedLaunchProfile`
  (JVM memory) with no re-check needed, it doesn't affect what's synced.
- **Update-check on startup and whenever you press Connect** (on the
  Server tab) — fetches the manifest and diffs it against local files
  (`ModSyncService.preview()`, read-only, nothing downloaded yet) and shows
  either "Up to date" (Play enabled) or "Update available (N files)"
  (Update enabled, Play hidden).
- **Update button** — runs the real sync (`ModSyncService.sync`) with
  live progress, then flips to the "Up to date" / Play-enabled state.
- **Play button** — only reachable once up to date; resolves the launch
  profile, downloads the client/libraries/assets (progress again), and
  launches Minecraft auto-connected to the active server, exactly like
  the console flow above. Re-enables itself once Minecraft exits so you
  can relaunch without restarting the launcher.
- A **progress bar** and **scrolling log** for the same
  `ThrottledProgressReporter` ticks the console prints, translated
  (`GuiProgressReporter`) — a friend with no context for "what's a
  ConnectException" still sees "更新があります" -> "更新中..." -> "更新が
  完了しました" -> a Play button.

**Server tab** — the editable connection form, touched once per server
(setup, or adding a new one), not on every launch:

- API Server URL, Game Server Address, Minecraft Version, Fabric Loader
  Version, and API Key are all plain editable fields. API Key is a
  regular (not password-masked) field on purpose — it's a shared secret
  you hand to friends and paste into a server startup command, not a
  personal login, so being able to see/copy it matters more than hiding
  it (see "Security" below for when it's needed).
- **Generate**, next to API Key — fills it with a fresh 24-byte
  URL-safe-base64 random value (`SecureRandom`), for when you're setting
  up `-Dmcsync.api.key=...` yourself and want a good value without making
  one up.
- A "Saved Server" dropdown exists for convenience (picking one copies its
  values into the fields as a starting point) but isn't required — type
  whatever a friend gave you and press **Connect** to apply it and
  re-check for updates, saved or not. Press **Save Preset** to add/update
  the current field values (keyed by the name typed into the dropdown) in
  the saved list for next time — this is separate from `activeServer`, so
  a Connect you haven't saved doesn't overwrite an existing preset.
- **Manager URL** and **Manager API Key** — where `server-manager`'s HTTP
  API lives (a separate process/port from sync-server, see "server-manager"
  above) and its own shared secret, used by the Server Manager tab below.
  Saved as part of the same `ServerProfile`/preset as everything else on
  this tab.

It runs the whole flow on a background worker thread and pushes UI updates
back via `Platform.runLater` — see `LauncherApp` for the state machine
(`checkForUpdates` -> `runUpdate` -> `runPlay`).

**Server Manager tab** — remote control of `server-manager`'s
`/api/manager/*` routes over HTTP (`ManagerClient`), so you don't need a
separate app or a terminal on the host to supervise the actual Fabric
server process:

- Read-only status: **State**, **Uptime**, **Process ID**, **Last Backup**,
  and **Crash Restarts**, pulled from `GET /api/manager/status`. `State` is
  shown exactly as `server-manager` reports it (`RUNNING`/`STOPPED`/etc.,
  untranslated) — it's deliberately a forward-compat plain string server-side,
  so a friend hosting will recognize it regardless of UI language.
- **Refresh** — re-pulls status and the recent-backups list on demand (no
  background polling, so it never hits an offline `server-manager`
  unprompted).
- **Start / Restart** — a single combined button, since
  `ServerProcessSupervisor.restart()` is just a (harmless if nothing's
  running) stop followed by a start; there's no separate "start" route.
- **Stop** — graceful RCON `stop`, falling back to a forced kill after
  `server-manager`'s own grace period.
- **Backup Now** — triggers an on-demand world backup
  (`POST /api/manager/backup`) and refreshes the list below it.
- A **Recent Backups** list (filename, size, timestamp) from
  `GET /api/manager/backups`.

Manager actions run on their own dedicated background thread (separate from
the Play/Update flow above), so a Stop/Restart click never races against —
or gets stuck behind — an in-progress mod sync.

**Host mode** — a checkbox at the top of this tab for anyone running the
actual Fabric server on the same PC as the launcher: when checked (with an
optional **Server Directory** field, passed through as `-Dmcsync.server.dir`
if set), the launcher auto-launches `server-manager` as a background process
(`LocalServiceLauncher`) by re-invoking its **own** packaged exe with a
`--server-manager` flag, which `LauncherMain` dispatches straight into
`ServerManagerApp` instead of the GUI — so the whole distributable is a
single exe, and day-to-day you only ever open one file. The checkbox is
greyed out when that self-re-invoke isn't possible (e.g. `:launcher:run`/IDE
debug, which has no packaged app image to relaunch). The spawned process is
deliberately *not* tied to the launcher's own lifetime (a plain Windows
child process outlives its parent, confirmed by killing the launcher
mid-test and watching the hosted process keep listening), which is also why
closing the launcher while host mode is on and the server is running shows a
**Minimize** (taskbar) / **Stop Server and Exit** / **Cancel** prompt
instead of silently killing the server out from under anyone still playing.

`LauncherApp` is the real `javafx.application.Application` subclass, but
`LauncherMain` (a plain class that just calls
`Application.launch(LauncherApp.class, args)`, or dispatches into
`ServerManagerApp.main()` instead when started with `--server-manager`) is
the actual entry point everywhere (jpackage, launch.json). A
classpath-launched (non-modular) main class that *directly* extends
`Application` gets refused at startup with "JavaFX runtime components are
missing" — `LauncherMain` sidesteps that.

To try it locally without a full jpackage build, use the VS Code "Debug
launcher (GUI)" config below, or build+run the packaged exe.

## Running the actual game server

See `server/README.md` — install the Fabric server jar there, accept the
EULA, drop mods into `server/mods/` and shared resource packs into
`server/resourcepacks/`, run `server/start.sh` (or `.bat`) — or, for
supervision and backups, `server-manager` (below).

## Server supervision & backups

`server-manager` is a 5th Gradle module that launches `server/`'s Fabric
process itself (`ProcessBuilder`, not `start.sh`/`.bat`), auto-restarts it
on an unexpected crash (with a crash-loop guard), and takes RCON-safe world
backups (`save-off` → zip `<level-name>/` + `server.properties`/
`whitelist.json`/`ops.json`/ban lists → `save-on`) on a schedule and
on-demand:

```sh
./gradlew :server-manager:run
```

It auto-configures RCON in `server/server.properties` on first run if it
isn't already enabled (generates `rcon.password` the same way the
launcher's API-key "Generate" button does) — see `server/README.md`
"Running via server-manager" for the plaintext-password-in-git caveat that
comes with this being a private repo that tracks `server/`.

System properties (all optional, defaults shown):

| Property | Default | |
|---|---|---|
| `mcsync.server.dir` | `../server` | |
| `mcsync.manager.port` | `7080` | sync-server already uses 7070 |
| `mcsync.manager.api.key` | unset (open) | see "Security" |
| `mcsync.manager.autostart` | `true` | start the server on server-manager's own startup |
| `mcsync.rcon.port` | `25575` | vanilla default |
| `mcsync.rcon.autoconfigure` | `true` | `false` fails fast instead of patching `server.properties` |
| `mcsync.server.jvm.minMemory` / `maxMemory` | `1G` / `4G` | matches `start.bat`/`.sh` |
| `mcsync.backup.interval.minutes` | `60` | scheduled backup cadence |
| `mcsync.backup.retention.count` | `10` | recent backups kept, older pruned |
| `mcsync.backup.dir` | `<server.dir>/backups` | |
| `mcsync.manager.crash.maxRestarts` / `.windowMinutes` | `5` / `10` | crash-loop guard |

As with `sync-server`, every one of these has to be added to
`server-manager/build.gradle.kts`'s `tasks.named<JavaExec>("run") {
systemProperties(...) }` forwarding block or `./gradlew`'s `-D` flags won't
reach the forked JVM.

HTTP endpoints (`Routes.MANAGER_*`, guarded by `mcsync.manager.api.key`
except health):

- `GET /api/manager/health` — always `"ok"`, unauthenticated
- `GET /api/manager/status` — `ManagerStatus` (state, uptime, pid, last
  backup time, crash-restart count)
- `POST /api/manager/backup` — trigger an on-demand backup, returns
  `BackupInfo`
- `GET /api/manager/backups` — list existing backups, newest first
- `POST /api/manager/stop` / `POST /api/manager/restart` — graceful control
  via RCON `stop`, falling back to a forced kill after a timeout

Resource monitoring (TPS/CPU/memory) and player join/leave notifications
are deliberately not built yet — see "Next steps".

## Debugging (VS Code)

`.vscode/launch.json` currently has one entry (Run and Debug panel, needs
the Java Extension Pack):

- **Debug launcher (GUI)** — runs `dev.haraguro.modserverplaymanager.launcher.gui.LauncherMain`
  (`-Dmcsync.downloadAssets=false` so stepping through code doesn't also
  trigger a multi-hundred-MB asset download), i.e. the actual window
  participants see. This is what you want for day-to-day GUI work.

Trimmed down on purpose to cut confusion while the GUI is the active focus —
other modules (`sync-server`, `server-manager`, the console `Launcher`) can
each still be run/debugged straight from their own file in the editor
(right-click the `main` method -> Debug), or add dedicated entries back to
`launch.json` when you actually need to step through one of them
side-by-side with the launcher.

`mod` isn't in launch.json: Fabric Loom generates `runClient`/`runServer`
JavaExec tasks with natives/asset paths computed at configure time, which
would be fragile to hand-copy into a static launch config. Instead, use the
**Gradle** side panel (Gradle for Java extension) -> `mod` -> `Tasks` ->
`fabric` -> right-click `runClient` (or `runServer`) -> **Debug Gradle
Task**.

## Packaging for distribution

Server participants shouldn't need Java or Gradle installed.

```sh
./gradlew :launcher:jpackageAppImage
```

Produces `launcher/build/jpackage/ModServerPlayManagerByHaraguro/`: a
standalone native app (bundled JRE, ~140 MB) with a **single** executable,
`ModServerPlayManagerByHaraguro.exe`. Double-clicking it opens the launcher
window and runs the full sync -> resolve -> download -> auto-connect flow
with no install step — this covers both the "just play" case and, via the
Server tab's host-mode checkbox, "host the Fabric server on this same PC"
(the launcher re-invokes this same exe with `--server-manager`, which
`LauncherMain` dispatches into `ServerManagerApp` instead of the GUI — see
"Host mode" above). Zip that one folder and hand it out, or run
`:launcher:releaseZip` (below) to do that automatically.

**Split across two machines** (server-manager on a headless host/VPS,
launcher on each player's own PC): the launcher build above already works
standalone for the player side. For the VPS side, build `server-manager` on
its own instead, so it doesn't drag in the ~120 MB JavaFX runtime it never
uses:

```sh
./gradlew :server-manager:jpackageAppImage  # server-manager/build/jpackage/ModServerPlayManagerByHaraguroService/
```

`./gradlew :launcher:releaseZip` runs the same `jpackageAppImage` build
above and also zips the result to
`launcher/build/distributions/ModServerPlayManagerByHaraguro-<version>-windows.zip`,
ready to attach to a GitHub release — see below.

## Development vs. release repos

Two GitHub repos, both under `haragurojyakku`:

- **`MinecraftSyncLauncher`** (`origin`) — the actual development repo:
  full source history, day-to-day commits. **Private**, and since it's
  private, `server/`'s runtime data (world, mods, jars, whitelist/ops) is
  tracked here too as a backup/sync mechanism instead of staying
  gitignored — see the "server/ runtime environment" note in `.gitignore`.
- **`MinecraftSyncLauncher_Releases`** (`releases` remote) — kept clean,
  only ever gets a push when cutting a tagged release (see below). This is
  what `UpdateChecker` polls and what participants' launchers link to, so
  it's not cluttered with dev history or (if you ever flip it to public)
  the private server backup data from the repo above.

## Releasing a new launcher version

The launcher checks GitHub Releases on every startup
(`dev.haraguro.modserverplaymanager.launcher.update.UpdateChecker`, hardcoded against
`MinecraftSyncLauncher_Releases`' `releases/latest` — see above, not this
dev repo) and shows a banner if the latest tag is newer than the running
build's own version. Cutting a release is:

1. Bump `version` in the root `gradle.properties` (plain `X.Y.Z`, no
   `-SNAPSHOT` — `AppVersion`/`UpdateChecker` do a numeric component
   comparison, so a stray suffix just gets dropped rather than compared).
2. `./gradlew :launcher:releaseZip`.
3. `git push releases main` (or just push the tag) so the release repo has
   something for the tag to point at.
4. On GitHub, draft a new release **on `MinecraftSyncLauncher_Releases`**
   tagged `vX.Y.Z` (the `v` prefix is stripped before comparing) and
   attach the zip from step 2.

`AppVersion.current()` reads the jar manifest's `Implementation-Version`
(set from `gradle.properties` in `launcher/build.gradle.kts`), which only
exists in the built jar — `:launcher:run`'s loose classpath has no
manifest, so dev runs report version `"dev"` and skip the check entirely.

### Launcher self-update

On Windows, the banner's button (`dev.haraguro.modserverplaymanager.launcher.update.SelfUpdater`)
downloads the matching release asset, extracts it, and hands off to a
detached helper batch script that waits for the running process to exit,
`robocopy /MIR`s the new files over the install directory, and relaunches
the exe — no manual re-download needed. This depends on `UpdateChecker`
finding a release asset whose name ends in `-windows.zip` (exactly what
`releaseZip` produces — step 2 above), so keep that suffix if you ever
rename the classifier in `launcher/build.gradle.kts`.

Non-Windows builds, and dev/IDE runs (no `Implementation-Version`), don't
support self-replace — they still just get the "Open Download Page" link
via `releaseUrl`.

## Security

Signing in with a real Microsoft account (see "Setting up Microsoft
sign-in" above) is the real alternative to `online-mode=false` +
whitelist — it lets the server verify who's actually behind a username the
same way vanilla does. Its refresh token is cached in plaintext in
`launcher-config.json`, more sensitive than the shared secrets below since
it's a personal credential; "Sign out" clears it.

For servers staying on `OfflineAuth` (see above) once reachable from the
internet rather than just a LAN, a few things close the actual gaps — full
details and commands in `server/README.md`'s "Exposing this to the
internet" section:

- **`-Dmcsync.api.key=...`** on `sync-server` — without it, anyone who can
  reach the port can read the mod list and player data (`SyncKeyAuth`,
  every route except `/api/health`). With it, the launcher's API Key field
  (`Routes.API_KEY_HEADER`, sent by `ModSyncService`) has to match. The
  launcher's Server tab has a **Generate** button next to that field if
  you want a good random value without making one up by hand.
- **`-Dmcsync.manager.api.key=...`** on `server-manager` — same idea, for
  `/api/manager/*` (`/api/manager/health` stays open). Arguably more
  sensitive than sync-server's key, since it can stop/restart the actual
  game server and trigger backups, not just read data. The launcher's
  Server tab has a **Manager API Key** field for this (no Generate button
  here, unlike the sync-server key above — this value has to match whatever
  you already fixed on the `server-manager` side via the flag, not
  something the launcher mints and you then copy elsewhere).
- **A Minecraft server whitelist** — `online-mode=false` means the server
  never checks who's really behind a username with Mojang, so without a
  whitelist anyone can claim any name. `./gradlew :launcher:generateWhitelist
  -PwhitelistNames=alice,bob` prints ready-to-paste `whitelist.json`
  entries using the same deterministic offline-UUID derivation as
  `OfflineAuth`.

None of these are needed once players sign in with a real Microsoft
account instead — they're the pragmatic stopgap for offline-mode,
friends-only servers.

## Next steps (not implemented yet)

- **launcher**: a proper installer (`--type msi`/`exe` instead of
  `app-image`) needs the WiX Toolset on the build machine — not installed
  here, so only the zip-and-share app-image is available today.
- **launcher (Microsoft sign-in)**: single cached account only — no
  account switcher/multi-account list, and the refresh token is stored in
  plaintext (no credential-store integration). Signing in with a different
  account silently overwrites the cached one.
- **sync-server**: player data is in-memory (`PlayerRoutes`) — swap in a real
  datastore before relying on it. Exchange rates are a static stub.
- **mod**: bank/emerald currency system implemented (see "Bank / emerald
  currency" above); beyond that, still just the API health check on
  startup.
- **launcher GUI**: launch profiles (JVM memory) are still select-only —
  adding or editing one means hand-editing `launcher-config.json`. Server
  connections are now a form (see "Launcher GUI" above), so this is just
  the remaining piece.
- **server-manager**: resource monitoring (TPS, CPU, memory) and player
  join/leave notifications were explicitly deferred — `ManagerStatus` and
  `ServerOutputPump`'s line-listener hook are shaped so these can slot in
  later as new fields/log matchers without a route-shape change, but no
  sampling logic or endpoints exist yet. `mod` (already loaded inside the
  server JVM) is the natural place to compute TPS/heap usage and POST it
  over, rather than external OS-level process inspection.
