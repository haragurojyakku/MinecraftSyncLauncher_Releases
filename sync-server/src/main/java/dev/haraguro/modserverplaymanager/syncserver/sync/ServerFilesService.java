package dev.haraguro.modserverplaymanager.syncserver.sync;

import dev.haraguro.modserverplaymanager.shared.model.ManagedFile;
import dev.haraguro.modserverplaymanager.shared.model.ModCategory;
import dev.haraguro.modserverplaymanager.shared.model.SyncFile;
import dev.haraguro.modserverplaymanager.shared.model.SyncManifest;
import dev.haraguro.modserverplaymanager.shared.model.SyncServerSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads and (for the admin add/remove/enable-disable operations) writes the
 * server/mods and server/resourcepacks directories that this project's
 * actual Fabric server runs from. This is the "distribute the server's mod
 * folder as-is" sync strategy: there is no separate mod manifest to
 * hand-maintain, whatever is in server/mods and server/resourcepacks (and
 * enabled) is exactly what gets synced to players — except mods explicitly
 * classified {@link ModCategory#SERVER_ONLY} via {@link #setModCategory}
 * (see {@link ModCategoryStore}), which stay on the server but are never
 * advertised to the launcher.
 *
 * {@link ModCategory#CLIENT_ONLY} mods physically live in the
 * {@value #CLIENT_ONLY_SUBDIR} subdirectory of server/mods rather than
 * server/mods itself: Fabric Loader's directory-based mod discovery only
 * scans the top level of the mods folder it's given, never subdirectories,
 * so a real dedicated server process pointed at server/mods will never load
 * anything sitting in {@value #CLIENT_ONLY_SUBDIR} — the same trick some
 * launchers use for a manual "mods/disabled" folder. That's what actually
 * keeps a client-only mod (which might not be well-behaved enough to safely
 * no-op if merely loaded server-side) off the running server, as opposed to
 * SERVER_ONLY/BOTH, which only ever affects what's advertised to players and
 * leaves the file exactly where enable/disable put it. setModCategory moves
 * the file between server/mods and server/mods/{@value #CLIENT_ONLY_SUBDIR}
 * as needed; every other method that touches "a mod by name" (resolve,
 * delete, toggle, list) checks both locations.
 *
 * A file is "disabled" by appending {@link #DISABLED_SUFFIX} to its real
 * name (the same convention MultiMC/Prism-style launchers use for client
 * mods) — disabled files stay on disk but are invisible to the player-facing
 * sync manifest ({@link #buildManifest}), while the admin listing
 * ({@link #listManagedMods}/{@link #listManagedResourcePacks}) still reports
 * them so the launcher's admin UI can re-enable them later.
 *
 * Whether uploading (addModFile/addResourcePackFile) is allowed to overwrite
 * an existing file of the same name (enabled or disabled) is controlled by
 * {@link SyncServerSettingsService#get()}'s preventOverwriteOnUpload flag —
 * off by default (the original behavior: uploads always land enabled,
 * replacing whatever was there); when turned on, a name collision throws
 * {@link FileAlreadyExistsException} instead of silently destroying whatever
 * was already there.
 */
public class ServerFilesService {

    private static final String DISABLED_SUFFIX = ".disabled";
    private static final String MOD_EXTENSION = ".jar";
    private static final String RESOURCE_PACK_EXTENSION = ".zip";
    static final String CLIENT_ONLY_SUBDIR = "client-only";

    private final Path modsDir;
    private final Path clientOnlyModsDir;
    private final Path resourcePacksDir;
    private final Path modListReportPath;
    private final SyncServerSettingsService settingsService;
    private final ModCategoryStore modCategories;

    public ServerFilesService(Path serverDir, SyncServerSettingsService settingsService) {
        this.modsDir = serverDir.resolve("mods");
        this.clientOnlyModsDir = modsDir.resolve(CLIENT_ONLY_SUBDIR);
        this.resourcePacksDir = serverDir.resolve("resourcepacks");
        this.modListReportPath = serverDir.resolve("MODS.md");
        this.settingsService = settingsService;
        this.modCategories = new ModCategoryStore(modsDir);
        migrateClientOnlyMods();
        writeModListReport();
    }

    public SyncManifest buildManifest(String minecraftVersion, String fabricLoaderVersion) {
        List<SyncFile> enabledMods = new ArrayList<>(listEnabled(modsDir, modCategories));
        enabledMods.addAll(listEnabled(clientOnlyModsDir, modCategories));
        List<SyncFile> disabledMods = new ArrayList<>(listDisabled(modsDir, modCategories));
        disabledMods.addAll(listDisabled(clientOnlyModsDir, modCategories));
        return new SyncManifest(minecraftVersion, fabricLoaderVersion,
                enabledMods, listEnabled(resourcePacksDir, null),
                disabledMods, listDisabled(resourcePacksDir, null));
    }

    public Optional<Path> resolveModFile(String fileName) {
        Optional<Path> found = resolveSafe(modsDir, fileName);
        return found.isPresent() ? found : resolveSafe(clientOnlyModsDir, fileName);
    }

    public Optional<Path> resolveResourcePackFile(String fileName) {
        return resolveSafe(resourcePacksDir, fileName);
    }

    // --- Admin: list (including disabled), add, delete, enable/disable ---

    public List<ManagedFile> listManagedMods() {
        List<ManagedFile> result = new ArrayList<>(listManaged(modsDir, modCategories));
        result.addAll(listManaged(clientOnlyModsDir, modCategories));
        return result;
    }

    public List<ManagedFile> listManagedResourcePacks() {
        return listManaged(resourcePacksDir, null);
    }

    /**
     * Reclassifies a mod as server-only, client-only, or both — see {@link ModCategory}. Changes
     * which future {@link #buildManifest} calls advertise it to players, and — moving into or out of
     * CLIENT_ONLY specifically — physically relocates the file between server/mods and
     * server/mods/{@value #CLIENT_ONLY_SUBDIR} (see the class doc) so the real dedicated server
     * process only ever sees mods it's actually supposed to load. The enabled/disabled state itself
     * (which of the two names the file has in its new location) is preserved across the move.
     *
     * @return the updated ManagedFile, or empty if no mod (enabled or disabled) with that name exists
     */
    public Optional<ManagedFile> setModCategory(String baseFileName, ModCategory category) throws IOException {
        if (!isSafeName(baseFileName) || category == null) {
            return Optional.empty();
        }
        Path currentDir = findModDir(baseFileName);
        if (currentDir == null) {
            return Optional.empty();
        }
        Path targetDir = category == ModCategory.CLIENT_ONLY ? clientOnlyModsDir : modsDir;
        if (!targetDir.equals(currentDir)) {
            moveModBetweenDirs(currentDir, targetDir, baseFileName);
        }
        modCategories.set(baseFileName, category);
        Path enabledPath = targetDir.resolve(baseFileName);
        Path disabledPath = targetDir.resolve(baseFileName + DISABLED_SUFFIX);
        ManagedFile result = describeManaged(Files.isRegularFile(enabledPath) ? enabledPath : disabledPath, modCategories);
        writeModListReport();
        return Optional.of(result);
    }

    /** Whichever of modsDir/clientOnlyModsDir currently holds this mod (enabled or disabled), or null. */
    private Path findModDir(String baseFileName) {
        if (Files.isRegularFile(modsDir.resolve(baseFileName)) || Files.isRegularFile(modsDir.resolve(baseFileName + DISABLED_SUFFIX))) {
            return modsDir;
        }
        if (Files.isRegularFile(clientOnlyModsDir.resolve(baseFileName)) || Files.isRegularFile(clientOnlyModsDir.resolve(baseFileName + DISABLED_SUFFIX))) {
            return clientOnlyModsDir;
        }
        return null;
    }

    private static void moveModBetweenDirs(Path fromDir, Path toDir, String baseFileName) throws IOException {
        Files.createDirectories(toDir);
        Path enabledFrom = fromDir.resolve(baseFileName);
        Path disabledFrom = fromDir.resolve(baseFileName + DISABLED_SUFFIX);
        if (Files.isRegularFile(enabledFrom)) {
            Files.move(enabledFrom, toDir.resolve(baseFileName), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.isRegularFile(disabledFrom)) {
            Files.move(disabledFrom, toDir.resolve(baseFileName + DISABLED_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * One-time startup reconciliation: a mod categorized CLIENT_ONLY before this launcher version
     * (when setModCategory didn't physically move anything yet) would still be sitting in modsDir —
     * move it into clientOnlyModsDir now so the invariant "CLIENT_ONLY mods never sit in modsDir"
     * holds from here on, without requiring the admin to re-touch every mod's category by hand.
     */
    private void migrateClientOnlyMods() {
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(modsDir)) {
            List<String> baseNames = stream
                    .filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> !name.startsWith("."))
                    .map(ServerFilesService::baseName)
                    .distinct()
                    .toList();
            for (String baseName : baseNames) {
                if (modCategories.get(baseName) == ModCategory.CLIENT_ONLY) {
                    moveModBetweenDirs(modsDir, clientOnlyModsDir, baseName);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String baseName(String fileName) {
        return fileName.endsWith(DISABLED_SUFFIX) ? fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length()) : fileName;
    }

    /**
     * Regenerates {@code server/MODS.md}, a human-readable snapshot of {@link #listManagedMods()}.
     * The launcher's admin UI already gets this live via {@code SYNC_MODS_LIST}, but that requires
     * running the launcher; this file lets anyone with server/repo access check what's installed
     * without it. Called after every mod mutation (add/delete/toggle/category change) plus once at
     * startup, so it's never stale.
     */
    private void writeModListReport() {
        List<ManagedFile> mods = listManagedMods().stream()
                .sorted(Comparator.comparing(ManagedFile::getFileName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        StringBuilder report = new StringBuilder();
        report.append("# server/mods 一覧\n\n");
        report.append("<!-- 自動生成ファイルです。手動で編集しないでください。\n");
        report.append("     sync-server が Mod の追加・削除・有効/無効切替・カテゴリ変更のたびに再生成します。 -->\n\n");
        report.append("生成日時: ").append(Instant.now()).append("\n\n");
        report.append("| Mod | サイズ | 状態 | カテゴリ |\n");
        report.append("|---|---|---|---|\n");
        for (ManagedFile mod : mods) {
            report.append("| ").append(mod.getFileName())
                    .append(" | ").append(formatSize(mod.getSizeBytes()))
                    .append(" | ").append(mod.isEnabled() ? "有効" : "無効")
                    .append(" | ").append(mod.getCategory())
                    .append(" |\n");
        }
        if (mods.isEmpty()) {
            report.append("| _(なし)_ | | | |\n");
        }

        try {
            Files.writeString(modListReportPath, report.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    /**
     * @throws IllegalArgumentException if fileName isn't a plain ".jar" name (no path separators/traversal)
     * @throws FileAlreadyExistsException if preventOverwriteOnUpload is on and a file with that name
     *         already exists (enabled or disabled) — the caller must remove/rename it first
     */
    public ManagedFile addModFile(String fileName, InputStream content) throws IOException {
        // Re-uploading a mod that's already classified CLIENT_ONLY must land back in
        // clientOnlyModsDir, not modsDir — otherwise it'd not just be miscategorized but actually
        // get loaded by the real dedicated server again, defeating the whole point of the split
        // directories (see the class doc). isSafeName-invalid names are a no-op here; addFile below
        // still does the real validation and throws.
        ModCategory category = isSafeName(fileName) ? modCategories.get(fileName) : ModCategory.BOTH;
        Path targetDir = category == ModCategory.CLIENT_ONLY ? clientOnlyModsDir : modsDir;
        Path otherDir = targetDir == modsDir ? clientOnlyModsDir : modsDir;
        deleteFile(otherDir, fileName); // clean up a stray same-named copy left behind in the other dir, if any
        ManagedFile result = addFile(targetDir, fileName, MOD_EXTENSION, content, modCategories);
        writeModListReport();
        return result;
    }

    /**
     * @throws IllegalArgumentException if fileName isn't a plain ".zip" name (no path separators/traversal)
     * @throws FileAlreadyExistsException if preventOverwriteOnUpload is on and a file with that name
     *         already exists (enabled or disabled) — the caller must remove/rename it first
     */
    public ManagedFile addResourcePackFile(String fileName, InputStream content) throws IOException {
        return addFile(resourcePacksDir, fileName, RESOURCE_PACK_EXTENSION, content, null);
    }

    public SyncServerSettings getSettings() {
        return settingsService.get();
    }

    public SyncServerSettings updateSettings(SyncServerSettings settings) throws IOException {
        return settingsService.update(settings);
    }

    /** @return true if a file (enabled or disabled) was actually deleted */
    public boolean deleteModFile(String baseFileName) throws IOException {
        boolean deleted = deleteFile(modsDir, baseFileName) | deleteFile(clientOnlyModsDir, baseFileName);
        if (deleted) {
            modCategories.remove(baseFileName);
            writeModListReport();
        }
        return deleted;
    }

    public boolean deleteResourcePackFile(String baseFileName) throws IOException {
        return deleteFile(resourcePacksDir, baseFileName);
    }

    public Optional<ManagedFile> toggleModFile(String baseFileName) throws IOException {
        Optional<ManagedFile> result = toggleFile(modsDir, baseFileName, modCategories);
        if (result.isEmpty()) {
            result = toggleFile(clientOnlyModsDir, baseFileName, modCategories);
        }
        if (result.isPresent()) {
            writeModListReport();
        }
        return result;
    }

    public Optional<ManagedFile> toggleResourcePackFile(String baseFileName) throws IOException {
        return toggleFile(resourcePacksDir, baseFileName, null);
    }

    private ManagedFile addFile(Path dir, String fileName, String requiredExtension, InputStream content,
                                 ModCategoryStore categoryStore) throws IOException {
        if (!isSafeName(fileName) || !fileName.toLowerCase(Locale.ROOT).endsWith(requiredExtension)) {
            throw new IllegalArgumentException(
                    "Invalid file name (must be a plain " + requiredExtension + " file, no path separators): " + fileName);
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        Path disabledTarget = dir.resolve(fileName + DISABLED_SUFFIX);
        // Only refuse the overwrite when the admin has explicitly opted in (preventOverwriteOnUpload) —
        // off by default, matching the original "uploads always land enabled" behavior. When on, a name
        // collision throws instead of destroying whatever was already there with no way to get it back;
        // see SyncRoutes for how this surfaces as a 409 to the launcher's admin UI.
        if (settingsService.get().isPreventOverwriteOnUpload() && (Files.exists(target) || Files.exists(disabledTarget))) {
            throw new FileAlreadyExistsException(fileName);
        }
        Files.deleteIfExists(disabledTarget);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return describeManaged(target, categoryStore);
    }

    private boolean deleteFile(Path dir, String baseFileName) throws IOException {
        if (!isSafeName(baseFileName)) {
            return false;
        }
        boolean deletedEnabled = Files.deleteIfExists(dir.resolve(baseFileName));
        boolean deletedDisabled = Files.deleteIfExists(dir.resolve(baseFileName + DISABLED_SUFFIX));
        return deletedEnabled || deletedDisabled;
    }

    private Optional<ManagedFile> toggleFile(Path dir, String baseFileName, ModCategoryStore categoryStore) throws IOException {
        if (!isSafeName(baseFileName)) {
            return Optional.empty();
        }
        Path enabledPath = dir.resolve(baseFileName);
        Path disabledPath = dir.resolve(baseFileName + DISABLED_SUFFIX);
        ModCategory category = categoryStore == null ? ModCategory.BOTH : categoryStore.get(baseFileName);
        if (Files.isRegularFile(enabledPath)) {
            Files.move(enabledPath, disabledPath, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(new ManagedFile(baseFileName, Files.size(disabledPath), false, category));
        }
        if (Files.isRegularFile(disabledPath)) {
            Files.move(disabledPath, enabledPath, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(new ManagedFile(baseFileName, Files.size(enabledPath), true, category));
        }
        return Optional.empty();
    }

    /** Rejects path traversal / directory escape attempts in a user-supplied file name. */
    private Optional<Path> resolveSafe(Path dir, String fileName) {
        if (!isSafeName(fileName)) {
            return Optional.empty();
        }
        Path resolved = dir.resolve(fileName).normalize();
        if (!resolved.startsWith(dir.normalize()) || !Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    private static boolean isSafeName(String fileName) {
        return fileName != null && !fileName.isBlank()
                && !fileName.contains("/") && !fileName.contains("\\") && !fileName.contains("..");
    }

    /**
     * Enabled files bound for the player-facing sync manifest. SERVER_ONLY mods are left out here —
     * they exist for the server to load, not for players to download — while BOTH and CLIENT_ONLY
     * mods (and, since resource packs have no category store, all resource packs) go through as before.
     */
    private List<SyncFile> listEnabled(Path dir, ModCategoryStore categoryStore) {
        List<SyncFile> result = new ArrayList<>();
        for (ManagedFile file : listManaged(dir, categoryStore)) {
            if (!file.isEnabled() || file.getCategory() == ModCategory.SERVER_ONLY) {
                continue;
            }
            try {
                result.add(new SyncFile(file.getFileName(), file.getSizeBytes(), sha1(dir.resolve(file.getFileName()))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return result;
    }

    /**
     * Disabled files, advertised to the launcher so it can mirror the disable locally (rename its own
     * copy aside) instead of either leaving a since-disabled mod active or deleting it outright — see
     * ModSyncService's reconciliation. size/sha1 describe the actual on-disk ".disabled" file. SERVER_ONLY
     * mods are excluded for the same reason as in {@link #listEnabled}.
     */
    private List<SyncFile> listDisabled(Path dir, ModCategoryStore categoryStore) {
        List<SyncFile> result = new ArrayList<>();
        for (ManagedFile file : listManaged(dir, categoryStore)) {
            if (file.isEnabled() || file.getCategory() == ModCategory.SERVER_ONLY) {
                continue;
            }
            try {
                result.add(new SyncFile(file.getFileName(), file.getSizeBytes(),
                        sha1(dir.resolve(file.getFileName() + DISABLED_SUFFIX))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return result;
    }

    private List<ManagedFile> listManaged(Path dir, ModCategoryStore categoryStore) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .map(file -> describeManaged(file, categoryStore))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ManagedFile describeManaged(Path file, ModCategoryStore categoryStore) {
        String name = file.getFileName().toString();
        boolean enabled = !name.endsWith(DISABLED_SUFFIX);
        String baseName = baseName(name);
        ModCategory category = categoryStore == null ? ModCategory.BOTH : categoryStore.get(baseName);
        try {
            return new ManagedFile(baseName, Files.size(file), enabled, category);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
