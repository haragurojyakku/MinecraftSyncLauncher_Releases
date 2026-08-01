package dev.haraguro.modserverplaymanager.launcher.sync;

import com.google.gson.Gson;
import dev.haraguro.modserverplaymanager.launcher.launch.ProgressListener;
import dev.haraguro.modserverplaymanager.shared.model.SyncFile;
import dev.haraguro.modserverplaymanager.shared.model.SyncManifest;
import dev.haraguro.modserverplaymanager.shared.protocol.Routes;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Mirrors the server's mods/resourcepacks folders, as advertised by
 * sync-server's sync manifest. This implements the "distribute the server's
 * mods folder as-is" sync strategy: the launcher mirrors server/mods and
 * server/resourcepacks, it does not maintain its own separate mod list.
 *
 * Three-way reconciliation against the manifest's enabled + disabled lists:
 * <ul>
 *   <li>enabled on the server, missing/stale locally — download it (or, if a
 *       locally-disabled copy already matches, just re-enable that copy
 *       instead of re-downloading — see DISABLED_SUFFIX below)</li>
 *   <li>disabled on the server but present (enabled) locally — the local
 *       copy is retired the same way sync-server retires it: renamed aside
 *       rather than deleted, so a later re-enable doesn't need a redownload</li>
 *   <li>tracked by neither list but present locally — deleted only if
 *       {@link SyncedFileStore} shows the server distributed this file at
 *       some point in the past (it's since been removed server-side
 *       entirely, not just disabled). A file the player dropped in
 *       themselves that sync-server has never listed in a manifest is left
 *       alone forever, so players can keep personal client-only mods/
 *       resource packs in the same folder without sync deleting them</li>
 * </ul>
 */
public class ModSyncService {

    // Must match ServerFilesService.DISABLED_SUFFIX (sync-server) — the two
    // modules don't share a dependency, so this is intentionally duplicated
    // rather than introducing a cross-module coupling for one string.
    private static final String DISABLED_SUFFIX = ".disabled";

    private final String apiBaseUrl;
    private final String apiKey;
    private final Path installDir;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    public ModSyncService(String apiBaseUrl, String apiKey, Path installDir) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.apiKey = apiKey;
        this.installDir = installDir;
    }

    /** @param progressFactory builds a fresh listener per phase, e.g. {@code ConsoleProgressReporter::new} */
    public SyncResult sync(Function<String, ProgressListener> progressFactory) throws IOException, InterruptedException {
        SyncManifest manifest = fetchManifest();

        int changed = 0;
        changed += reconcile(manifest.getMods(), manifest.getDisabledMods(), "mods",
                Routes.SYNC_MODS_DOWNLOAD, progressFactory.apply("mods"));
        changed += reconcile(manifest.getResourcePacks(), manifest.getDisabledResourcePacks(), "resourcepacks",
                Routes.SYNC_RESOURCEPACKS_DOWNLOAD, progressFactory.apply("resourcepacks"));

        return new SyncResult(manifest, changed);
    }

    /** Same diff as {@link #sync}, but read-only — for "is there an update?" UI checks. */
    public PreviewResult preview() throws IOException, InterruptedException {
        SyncManifest manifest = fetchManifest();
        int outOfDate = countPendingChanges(manifest.getMods(), manifest.getDisabledMods(), "mods")
                + countPendingChanges(manifest.getResourcePacks(), manifest.getDisabledResourcePacks(), "resourcepacks");
        return new PreviewResult(manifest, outOfDate);
    }

    /**
     * Deletes everything this launcher previously downloaded into installDir/mods and
     * installDir/resourcepacks on the server's behalf (not the directories themselves, and not any
     * personal files the player added there — see SyncedFileStore). Meant for testing the update flow
     * from a clean slate — after this, every server-tracked file in the manifest counts as out of
     * date again.
     */
    public void cleanupSyncedFiles() throws IOException {
        deleteManagedFiles(installDir.resolve("mods"));
        deleteManagedFiles(installDir.resolve("resourcepacks"));
    }

    private static void deleteManagedFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        SyncedFileStore syncedFiles = new SyncedFileStore(dir);
        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                String base = baseName(name);
                if (syncedFiles.isManaged(base)) {
                    Files.deleteIfExists(file);
                    syncedFiles.forget(base);
                }
            }
        }
        syncedFiles.save();
    }

    private SyncManifest fetchManifest() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(URI.create(apiBaseUrl + Routes.SYNC_MANIFEST))
                        .timeout(Duration.ofSeconds(10)))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch sync manifest: HTTP " + response.statusCode());
        }
        return gson.fromJson(response.body(), SyncManifest.class);
    }

    /** What (if anything) a single enabled manifest entry needs done locally. */
    private enum EnabledAction { UP_TO_DATE, RENAME_FROM_DISABLED, DOWNLOAD }

    private EnabledAction decideEnabledAction(Path targetDir, SyncFile file) throws IOException {
        Path enabledPath = targetDir.resolve(file.getFileName());
        if (isUpToDate(enabledPath, file)) {
            return EnabledAction.UP_TO_DATE;
        }
        Path disabledPath = targetDir.resolve(file.getFileName() + DISABLED_SUFFIX);
        if (Files.exists(disabledPath) && isUpToDate(disabledPath, file)) {
            return EnabledAction.RENAME_FROM_DISABLED;
        }
        return EnabledAction.DOWNLOAD;
    }

    /** True if the server disabled this file but the local copy is still (only) present enabled. */
    private static boolean needsLocalDisable(Path targetDir, SyncFile file) {
        Path enabledPath = targetDir.resolve(file.getFileName());
        Path disabledPath = targetDir.resolve(file.getFileName() + DISABLED_SUFFIX);
        return Files.exists(enabledPath) && !Files.exists(disabledPath);
    }

    /**
     * Local files (enabled or disabled form) whose base name isn't tracked by the server at all
     * anymore. Dotfiles are skipped — that's SyncedFileStore's own bookkeeping file, not a mod.
     */
    private static List<Path> extraLocalFiles(Path targetDir, List<SyncFile> enabled, List<SyncFile> disabled) throws IOException {
        if (!Files.isDirectory(targetDir)) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        enabled.forEach(f -> known.add(f.getFileName()));
        disabled.forEach(f -> known.add(f.getFileName()));

        List<Path> extra = new ArrayList<>();
        try (var stream = Files.list(targetDir)) {
            for (Path local : stream.toList()) {
                String name = local.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                String baseName = baseName(name);
                if (!known.contains(baseName)) {
                    extra.add(local);
                }
            }
        }
        return extra;
    }

    private static String baseName(String fileName) {
        return fileName.endsWith(DISABLED_SUFFIX)
                ? fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length()) : fileName;
    }

    private int reconcile(List<SyncFile> enabledFiles, List<SyncFile> disabledFiles, String subDir,
                           String downloadRouteTemplate, ProgressListener listener) throws IOException, InterruptedException {
        List<SyncFile> enabled = enabledFiles == null ? List.of() : enabledFiles;
        List<SyncFile> disabled = disabledFiles == null ? List.of() : disabledFiles;

        Path targetDir = installDir.resolve(subDir);
        Files.createDirectories(targetDir);
        SyncedFileStore syncedFiles = new SyncedFileStore(targetDir);

        int changed = 0;
        int completed = 0;
        for (SyncFile file : enabled) {
            Path enabledPath = targetDir.resolve(file.getFileName());
            Path disabledPath = targetDir.resolve(file.getFileName() + DISABLED_SUFFIX);
            switch (decideEnabledAction(targetDir, file)) {
                case RENAME_FROM_DISABLED -> {
                    Files.move(disabledPath, enabledPath, StandardCopyOption.REPLACE_EXISTING);
                    changed++;
                }
                case DOWNLOAD -> {
                    Files.deleteIfExists(disabledPath); // stale/mismatched leftover, about to be superseded
                    downloadTo(enabledPath, file, downloadRouteTemplate);
                    changed++;
                }
                case UP_TO_DATE -> {
                }
            }
            // The server currently distributes this file (enabled or not) — from now on it's fair
            // game for the "extra local file" cleanup below if the server later drops it entirely.
            syncedFiles.markManaged(file.getFileName());
            listener.onFileComplete(++completed, enabled.size());
        }

        for (SyncFile file : disabled) {
            if (needsLocalDisable(targetDir, file)) {
                Files.move(targetDir.resolve(file.getFileName()), targetDir.resolve(file.getFileName() + DISABLED_SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
                changed++;
            }
            syncedFiles.markManaged(file.getFileName());
        }

        // Only delete extras the server has claimed ownership of in the past (see SyncedFileStore) —
        // a file it's never distributed is a player's own personal mod/resource pack, left alone.
        for (Path extra : extraLocalFiles(targetDir, enabled, disabled)) {
            String baseName = baseName(extra.getFileName().toString());
            if (!syncedFiles.isManaged(baseName)) {
                continue;
            }
            Files.delete(extra);
            syncedFiles.forget(baseName);
            changed++;
        }

        syncedFiles.save();
        return changed;
    }

    private int countPendingChanges(List<SyncFile> enabledFiles, List<SyncFile> disabledFiles, String subDir) throws IOException {
        List<SyncFile> enabled = enabledFiles == null ? List.of() : enabledFiles;
        List<SyncFile> disabled = disabledFiles == null ? List.of() : disabledFiles;
        Path targetDir = installDir.resolve(subDir);
        SyncedFileStore syncedFiles = new SyncedFileStore(targetDir);

        int count = 0;
        for (SyncFile file : enabled) {
            if (decideEnabledAction(targetDir, file) != EnabledAction.UP_TO_DATE) {
                count++;
            }
        }
        for (SyncFile file : disabled) {
            if (needsLocalDisable(targetDir, file)) {
                count++;
            }
        }
        // Mirrors reconcile()'s "only extras the server has claimed ownership of in the past" rule —
        // read-only here, doesn't persist anything (see SyncedFileStore).
        for (Path extra : extraLocalFiles(targetDir, enabled, disabled)) {
            if (syncedFiles.isManaged(baseName(extra.getFileName().toString()))) {
                count++;
            }
        }
        return count;
    }

    private void downloadTo(Path destination, SyncFile file, String downloadRouteTemplate) throws IOException, InterruptedException {
        String downloadPath = downloadRouteTemplate.replace("{file}", encodeFileName(file.getFileName()));
        HttpRequest request = withApiKey(HttpRequest.newBuilder(URI.create(apiBaseUrl + downloadPath))
                        .timeout(Duration.ofMinutes(2)))
                .GET()
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download " + file.getFileName() + ": HTTP " + response.statusCode());
        }
        Files.write(destination, response.body());
    }

    private HttpRequest.Builder withApiKey(HttpRequest.Builder builder) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(Routes.API_KEY_HEADER, apiKey);
        }
        return builder;
    }

    /**
     * Path-segment-encodes a file name for substitution into a {file}
     * route template — mirrors ServerFilesAdminClient.itemUri(). Without
     * this, any mod/resource pack whose file name has a space or other
     * URI-reserved character (not unusual — Fabric API version strings
     * contain "+", and some resource packs are named with spaces) breaks
     * URI.create() with "Illegal character in path".
     */
    private static String encodeFileName(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private boolean isUpToDate(Path localPath, SyncFile expected) throws IOException {
        if (!Files.exists(localPath)) {
            return false;
        }
        if (Files.size(localPath) != expected.getSize()) {
            return false;
        }
        return sha1(localPath).equalsIgnoreCase(expected.getSha1());
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    public record SyncResult(SyncManifest manifest, int changesApplied) {
    }

    public record PreviewResult(SyncManifest manifest, int outOfDateCount) {
        public boolean isUpToDate() {
            return outOfDateCount == 0;
        }
    }
}
