package dev.haraguro.modserverplaymanager.launcher.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Checks GitHub Releases for a newer launcher version than the one
 * currently running. {@link UpdateInfo#assetUrl}, when present, points at
 * the platform-matching zip asset for {@link SelfUpdater} to download and
 * install in place; {@link UpdateInfo#releaseUrl} is the fallback the GUI
 * opens in a browser when self-update isn't available (non-Windows, a
 * dev/IDE run, or a release with no asset attached yet).
 */
public class UpdateChecker {

    private static final String REPO = "haragurojyakku/MinecraftSyncLauncher_Releases";
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Optional<UpdateInfo> checkForUpdate() throws IOException, InterruptedException {
        String current = AppVersion.current();
        if ("dev".equals(current)) {
            // Not running from the built jar (e.g. :launcher:run) — no
            // Implementation-Version to compare against, nothing to do.
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty(); // repo has no releases published yet
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub releases check failed: HTTP " + response.statusCode());
        }

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String tag = release.get("tag_name").getAsString();
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        String htmlUrl = release.get("html_url").getAsString();
        String assetUrl = SelfUpdater.isSupported() ? findWindowsAssetUrl(release) : null;

        return isNewer(latest, current) ? Optional.of(new UpdateInfo(latest, htmlUrl, assetUrl)) : Optional.empty();
    }

    /**
     * Matches {@code releaseZip}'s {@code ModServerPlayManagerByHaraguro-<version>-windows.zip}
     * naming (see launcher/build.gradle.kts) — null if a release was tagged
     * without an asset attached yet, which the manual "draft a release,
     * attach the zip" process makes a real possibility, not just theoretical.
     */
    private static String findWindowsAssetUrl(JsonObject release) {
        JsonArray assets = release.getAsJsonArray("assets");
        if (assets == null) {
            return null;
        }
        for (var element : assets) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (name.endsWith("-windows.zip")) {
                return asset.get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    /** Plain X.Y.Z numeric comparison — good enough for this project's tags. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) {
                return ai > bi;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String numericPart = version.split("-")[0]; // drop any -SNAPSHOT/-beta suffix
        String[] parts = numericPart.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    /** @param assetUrl the Windows zip asset to self-update from, or null if unavailable (see {@link #findWindowsAssetUrl}) */
    public record UpdateInfo(String version, String releaseUrl, String assetUrl) {
    }
}
