package dev.haraguro.modserverplaymanager.launcher.admin;

import dev.haraguro.modserverplaymanager.shared.protocol.Routes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Uploads a player's custom skin PNG to sync-server's SkinRoutes (see
 * SkinStorageService) — the reverse direction of ServerFilesAdminClient
 * (mod/resourcepack admin), kept separate since a skin is keyed by player
 * UUID rather than by a file name and is always overwritten, never listed
 * or deleted individually.
 */
public class PlayerSkinClient {

    private final String apiBaseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public PlayerSkinClient(String apiBaseUrl, String apiKey) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.apiKey = apiKey;
    }

    public void uploadSkin(UUID uuid, Path pngFile) throws IOException, InterruptedException {
        String boundary = "mcsync-" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, pngFile.getFileName().toString(), Files.readAllBytes(pngFile));

        HttpRequest request = withApiKey(HttpRequest.newBuilder(skinUri(uuid)).timeout(Duration.ofMinutes(2)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to upload skin: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    /** Builds a single-part "file" multipart/form-data body — mirrors ServerFilesAdminClient.buildMultipartBody. */
    private static byte[] buildMultipartBody(String boundary, String fileName, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
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

    private URI skinUri(UUID uuid) {
        return URI.create(apiBaseUrl + Routes.PLAYER_SKIN.replace("{uuid}", uuid.toString()));
    }
}
