package dev.haraguro.modserverplaymanager.launcher.admin;

import com.google.gson.Gson;
import dev.haraguro.modserverplaymanager.shared.model.ManagedFile;
import dev.haraguro.modserverplaymanager.shared.model.ModCategory;
import dev.haraguro.modserverplaymanager.shared.model.SyncServerSettings;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HTTP client for sync-server's Mod/resource pack admin endpoints (add/
 * remove/enable-disable server/mods and server/resourcepacks — the reverse
 * direction of {@link dev.haraguro.modserverplaymanager.launcher.sync.ModSyncService},
 * which only ever downloads). java.net.http.HttpClient has no native
 * multipart support, so uploads build a minimal multipart/form-data body by
 * hand rather than pulling in a whole HTTP library for one request shape.
 */
public class ServerFilesAdminClient {

    private final String apiBaseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    public ServerFilesAdminClient(String apiBaseUrl, String apiKey) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.apiKey = apiKey;
    }

    public List<ManagedFile> listMods() throws IOException, InterruptedException {
        return list(Routes.SYNC_MODS_LIST);
    }

    public List<ManagedFile> listResourcePacks() throws IOException, InterruptedException {
        return list(Routes.SYNC_RESOURCEPACKS_LIST);
    }

    public ManagedFile uploadMod(Path file) throws IOException, InterruptedException {
        return upload(Routes.SYNC_MODS_LIST, file);
    }

    public ManagedFile uploadResourcePack(Path file) throws IOException, InterruptedException {
        return upload(Routes.SYNC_RESOURCEPACKS_LIST, file);
    }

    public void deleteMod(String fileName) throws IOException, InterruptedException {
        delete(Routes.SYNC_MODS_DOWNLOAD, fileName);
    }

    public void deleteResourcePack(String fileName) throws IOException, InterruptedException {
        delete(Routes.SYNC_RESOURCEPACKS_DOWNLOAD, fileName);
    }

    public ManagedFile toggleMod(String fileName) throws IOException, InterruptedException {
        return toggle(Routes.SYNC_MODS_TOGGLE, fileName);
    }

    /** Reclassifies a mod as server-only, client-only, or both — see {@link ModCategory}. */
    public ManagedFile setModCategory(String fileName, ModCategory category) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(itemUri(Routes.SYNC_MODS_CATEGORY, fileName))
                        .timeout(Duration.ofSeconds(10)))
                .method("PUT", HttpRequest.BodyPublishers.ofString(gson.toJson(new CategoryUpdateRequest(category))))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to set category for " + fileName + ": HTTP " + response.statusCode() + " " + response.body());
        }
        return gson.fromJson(response.body(), ManagedFile.class);
    }

    public ManagedFile toggleResourcePack(String fileName) throws IOException, InterruptedException {
        return toggle(Routes.SYNC_RESOURCEPACKS_TOGGLE, fileName);
    }

    public SyncServerSettings getSyncSettings() throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.SYNC_SETTINGS)).timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch sync-server settings: HTTP " + response.statusCode() + " " + response.body());
        }
        return gson.fromJson(response.body(), SyncServerSettings.class);
    }

    public SyncServerSettings updateSyncSettings(SyncServerSettings settings) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(Routes.SYNC_SETTINGS)).timeout(Duration.ofSeconds(10)))
                .method("PUT", HttpRequest.BodyPublishers.ofString(gson.toJson(settings)))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to update sync-server settings: HTTP " + response.statusCode() + " " + response.body());
        }
        return gson.fromJson(response.body(), SyncServerSettings.class);
    }

    private List<ManagedFile> list(String path) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to list " + path + ": HTTP " + response.statusCode() + " " + response.body());
        }
        ManagedFile[] files = gson.fromJson(response.body(), ManagedFile[].class);
        return files == null ? new ArrayList<>() : List.of(files);
    }

    private ManagedFile upload(String path, Path file) throws IOException, InterruptedException {
        String boundary = "mcsync-" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, file.getFileName().toString(), Files.readAllBytes(file));

        HttpRequest request = withApiKey(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofMinutes(2)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // 409 = a file with that name already exists (enabled or disabled) — ServerFilesService
        // never overwrites, so surface its clear error message rather than a raw "HTTP 409 {...}".
        if (response.statusCode() == 409) {
            throw new IOException(extractErrorMessage(response.body(),
                    "A file named \"" + file.getFileName() + "\" already exists on the server."));
        }
        if (response.statusCode() != 200) {
            throw new IOException("Failed to upload " + file.getFileName() + ": HTTP " + response.statusCode() + " " + response.body());
        }
        return gson.fromJson(response.body(), ManagedFile.class);
    }

    private String extractErrorMessage(String responseBody, String fallback) {
        try {
            java.util.Map<?, ?> parsed = gson.fromJson(responseBody, java.util.Map.class);
            Object error = parsed != null ? parsed.get("error") : null;
            return error != null ? error.toString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void delete(String pathTemplate, String fileName) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(itemUri(pathTemplate, fileName)).timeout(Duration.ofSeconds(10)))
                .DELETE()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw new IOException("Failed to delete " + fileName + ": HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private ManagedFile toggle(String pathTemplate, String fileName) throws IOException, InterruptedException {
        HttpRequest request = withApiKey(HttpRequest.newBuilder(itemUri(pathTemplate, fileName))
                        .timeout(Duration.ofSeconds(10)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to toggle " + fileName + ": HTTP " + response.statusCode() + " " + response.body());
        }
        return gson.fromJson(response.body(), ManagedFile.class);
    }

    /** Builds a single-part "file" multipart/form-data body — the only shape sync-server's upload routes expect. */
    private static byte[] buildMultipartBody(String boundary, String fileName, byte[] content) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private HttpRequest.Builder withApiKey(HttpRequest.Builder builder) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(Routes.API_KEY_HEADER, apiKey);
        }
        return builder;
    }

    private URI uri(String path) {
        return URI.create(apiBaseUrl + path);
    }

    /** Substitutes a {file} path template placeholder with a path-segment-encoded (not query-encoded) file name. */
    private URI itemUri(String pathTemplate, String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(apiBaseUrl + pathTemplate.replace("{file}", encoded));
    }

    private record CategoryUpdateRequest(ModCategory category) {
    }
}
