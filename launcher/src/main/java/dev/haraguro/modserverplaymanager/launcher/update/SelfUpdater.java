package dev.haraguro.modserverplaymanager.launcher.update;

import dev.haraguro.modserverplaymanager.launcher.launch.ProgressListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the platform-matching release asset, extracts it, and hands off
 * to a detached helper script that waits for this process to exit, mirrors
 * the new files over the running install directory, and relaunches — the
 * real self-update {@link UpdateChecker}'s javadoc describes as "left for
 * later". Windows-only: a running jpackage app-image exe can't overwrite
 * its own locked files, so the actual replace has to happen from outside
 * the JVM; other platforms fall back to {@link UpdateChecker}'s
 * browser-link behavior (see {@link #isSupported()}).
 */
public class SelfUpdater {

    private static final String EXE_NAME = "ModServerPlayManagerByHaraguro.exe";

    // GitHub's /releases/download/... asset URLs are a 302 to the actual CDN object (objects.githubusercontent.com)
    // — HttpClient defaults to Redirect.NEVER, which surfaced as "HTTP 302" download failures in practice.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * True only when this process is actually the running app-image exe —
     * false for {@code :launcher:run}/IDE debug (no manifest, launched via
     * a plain java process) and non-Windows builds, both of which should
     * fall back to {@link UpdateChecker}'s browser-link path instead.
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") && currentExePath().isPresent();
    }

    private static Optional<Path> currentExePath() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(EXE_NAME));
    }

    /**
     * Streams the asset zip to a temp file (reporting completed/total
     * bytes, rounded to whole MB, through {@code progress} — same
     * {@link ProgressListener} the mod/asset sync flows already use) and
     * extracts it. Returns the extracted {@code ModServerPlayManagerByHaraguro/}
     * app-image folder (the zip's single top-level dir, matching
     * {@code releaseZip}'s layout).
     */
    public Path downloadAndExtract(String assetUrl, ProgressListener progress) throws IOException, InterruptedException {
        cleanupStaleTempDirs();

        Path workDir = Files.createTempDirectory("mcsync-update-");
        Path zipFile = workDir.resolve("update.zip");
        Path extractDir = workDir.resolve("extracted");

        HttpRequest request = HttpRequest.newBuilder(URI.create(assetUrl))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download " + assetUrl + ": HTTP " + response.statusCode());
        }
        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(zipFile)) {
            byte[] buffer = new byte[64 * 1024];
            long downloaded = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                if (totalBytes > 0) {
                    progress.onFileComplete((int) (downloaded / (1024 * 1024)), (int) Math.max(1, totalBytes / (1024 * 1024)));
                }
            }
        }

        extract(zipFile, extractDir);

        try (var topLevel = Files.list(extractDir)) {
            return topLevel.filter(Files::isDirectory).findFirst()
                    .orElseThrow(() -> new IOException("Update zip had no top-level app folder"));
        }
    }

    /**
     * Resolves this process's own install dir/PID, writes a helper batch
     * script that waits for this process to exit, mirrors
     * {@code extractedAppDir} over it, and relaunches the exe — then
     * launches it detached. Callers should call {@code Platform.exit()}
     * (or equivalent) immediately after this returns.
     */
    public void relaunchAndExit(Path extractedAppDir) throws IOException {
        Path exePath = currentExePath().orElseThrow(() -> new IllegalStateException("Not running as " + EXE_NAME));
        Path installRoot = exePath.getParent();
        long pid = ProcessHandle.current().pid();

        Path batFile = extractedAppDir.getParent().getParent().resolve("apply-update.bat");
        String script = """
                @echo off
                setlocal
                :wait
                tasklist /FI "PID eq %d" 2>NUL | find "%d" >NUL
                if not errorlevel 1 (
                    timeout /t 1 /nobreak >NUL
                    goto wait
                )
                robocopy "%s" "%s" /MIR /R:3 /W:1 /NFL /NDL /NJH /NJS >NUL
                start "" "%s"
                """.formatted(pid, pid, extractedAppDir, installRoot, installRoot.resolve(EXE_NAME));
        Files.writeString(batFile, script, StandardCharsets.UTF_8);

        new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", batFile.toAbsolutePath().toString())
                .directory(batFile.getParent().toFile())
                .start();
    }

    private static void extract(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path normalizedTarget = targetDir.normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = normalizedTarget.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(normalizedTarget)) {
                    throw new IOException("Update zip entry escapes target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zip, resolved);
                }
            }
        }
    }

    private static void cleanupStaleTempDirs() {
        Path tempBase = Path.of(System.getProperty("java.io.tmpdir"));
        try (var stream = Files.list(tempBase)) {
            stream.filter(p -> p.getFileName().toString().startsWith("mcsync-update-"))
                    .forEach(SelfUpdater::deleteRecursivelyQuietly);
        } catch (IOException ignored) {
            // Best-effort cleanup only — a leftover temp dir from a previous
            // run isn't worth failing this one over.
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
