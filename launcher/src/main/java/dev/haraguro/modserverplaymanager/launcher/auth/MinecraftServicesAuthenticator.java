package dev.haraguro.modserverplaymanager.launcher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** The last hop: trade an XSTS token for a Minecraft access token, then fetch the profile (name + UUID). */
public class MinecraftServicesAuthenticator {

    private static final String LOGIN_WITH_XBOX_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record MinecraftProfileInfo(UUID id, String name) {
    }

    public String loginWithXbox(String userHash, String xstsToken) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        HttpRequest request = HttpRequest.newBuilder(URI.create(LOGIN_WITH_XBOX_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Minecraft login_with_xbox failed: HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject().get("access_token").getAsString();
    }

    /** A 404 here means the account doesn't own the game — surfaced as GAME_NOT_OWNED rather than a generic failure. */
    public MinecraftProfileInfo fetchProfile(String mcAccessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILE_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + mcAccessToken)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new MsaAuthException(MsaAuthException.Reason.GAME_NOT_OWNED, "This Microsoft account doesn't own Minecraft.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Minecraft profile fetch failed: HTTP " + response.statusCode());
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        return new MinecraftProfileInfo(UUID.fromString(dashUuid(body.get("id").getAsString())), body.get("name").getAsString());
    }

    /** The profile endpoint returns a dash-less UUID; java.util.UUID.fromString needs the standard 8-4-4-4-12 form. */
    private static String dashUuid(String raw) {
        return raw.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5");
    }
}
