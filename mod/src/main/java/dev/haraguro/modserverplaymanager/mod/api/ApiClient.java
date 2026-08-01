package dev.haraguro.modserverplaymanager.mod.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal HTTP client the mod uses to reach sync-server (player data,
 * external services). Kept dependency-free beyond what Minecraft already
 * ships (java.net.http, Gson) so the mod jar needs no shading.
 */
public class ApiClient {

    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public CompletableFuture<Boolean> healthCheckAsync() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> response.statusCode() == 200);
    }

    public CompletableFuture<JsonObject> fetchPlayerProfileAsync(UUID uuid) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/players/" + uuid))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("sync-server returned HTTP " + response.statusCode() + " for player " + uuid);
                    }
                    return GSON.fromJson(response.body(), JsonObject.class);
                });
    }

    // Path strings below intentionally duplicated rather than depending on :shared's Routes
    // (see this mod's build.gradle.kts note on why the mod talks HTTP/JSON instead of sharing
    // classes) — keep in sync with Routes.PLAYER_SKIN / PLAYER_SKIN_HASH.

    /** Empty if the player has no custom skin uploaded (sync-server 404). */
    public CompletableFuture<Optional<String>> fetchSkinHashAsync(UUID uuid) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/players/" + uuid + "/skin/hash"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 404) {
                        return Optional.empty();
                    }
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("sync-server returned HTTP " + response.statusCode() + " for skin hash " + uuid);
                    }
                    return Optional.of(GSON.fromJson(response.body(), JsonObject.class).get("sha1").getAsString());
                });
    }

    // Balance endpoints — path strings duplicated for the same reason as the skin
    // endpoints above; keep in sync with Routes.PLAYER_BALANCE_DEPOSIT/WITHDRAW/TRANSFER.
    // Callers on the server side only: these mutate a shared account, so the dedicated
    // server (not the client) must be the one deciding when they fire.

    public CompletableFuture<JsonObject> depositAsync(UUID uuid, double amount) {
        return postAmount(baseUrl + "/api/players/" + uuid + "/balance/deposit", amount);
    }

    /** Future fails with {@link InsufficientBalanceException} if the account can't cover amount. */
    public CompletableFuture<JsonObject> withdrawAsync(UUID uuid, double amount) {
        return postAmount(baseUrl + "/api/players/" + uuid + "/balance/withdraw", amount);
    }

    /** Future fails with {@link InsufficientBalanceException} if fromUuid can't cover amount. */
    public CompletableFuture<JsonObject> transferAsync(UUID fromUuid, UUID toUuid, double amount) {
        JsonObject body = new JsonObject();
        body.addProperty("toUuid", toUuid.toString());
        body.addProperty("amount", amount);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/players/" + fromUuid + "/balance/transfer"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return sendBalanceRequest(request);
    }

    private CompletableFuture<JsonObject> postAmount(String url, double amount) {
        JsonObject body = new JsonObject();
        body.addProperty("amount", amount);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return sendBalanceRequest(request);
    }

    private CompletableFuture<JsonObject> sendBalanceRequest(HttpRequest request) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 409) {
                        throw new InsufficientBalanceException(response.body());
                    }
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("sync-server returned HTTP " + response.statusCode()
                                + " for " + request.uri() + ": " + response.body());
                    }
                    return GSON.fromJson(response.body(), JsonObject.class);
                });
    }

    /** Empty if the player has no custom skin uploaded (sync-server 404). */
    public CompletableFuture<Optional<byte[]>> fetchSkinBytesAsync(UUID uuid) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/players/" + uuid + "/skin"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() == 404) {
                        return Optional.empty();
                    }
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("sync-server returned HTTP " + response.statusCode() + " for skin " + uuid);
                    }
                    return Optional.of(response.body());
                });
    }

    /** Thrown (wrapped in the returned future) when a withdraw/transfer exceeds the account's balance. */
    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String syncServerMessage) {
            super(syncServerMessage);
        }
    }
}
