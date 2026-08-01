package dev.haraguro.modserverplaymanager.launcher.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Talks to login.microsoftonline.com's device-code grant — the "sign in on your other device" flow. */
public class MicrosoftDeviceCodeAuth {

    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String SCOPE = "XboxLive.signin offline_access";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record DeviceCodePrompt(String deviceCode, String userCode, String verificationUri,
                                    int intervalSeconds, Instant expiresAt) {
    }

    public record MicrosoftTokens(String accessToken, String refreshToken, Instant expiresAt) {
    }

    public DeviceCodePrompt requestDeviceCode(String clientId) throws IOException, InterruptedException {
        JsonObject body = postForm(DEVICE_CODE_URL, "client_id=" + encode(clientId) + "&scope=" + encode(SCOPE));
        return new DeviceCodePrompt(
                body.get("device_code").getAsString(),
                body.get("user_code").getAsString(),
                body.get("verification_uri").getAsString(),
                body.has("interval") ? body.get("interval").getAsInt() : 5,
                Instant.now().plusSeconds(body.get("expires_in").getAsLong()));
    }

    /** Blocks, sleeping between attempts, until the user approves, the code expires, or they decline. */
    public MicrosoftTokens pollForToken(String clientId, DeviceCodePrompt prompt) throws IOException, InterruptedException {
        String form = "grant_type=" + encode("urn:ietf:params:oauth:grant-type:device_code")
                + "&client_id=" + encode(clientId) + "&device_code=" + encode(prompt.deviceCode());
        long intervalMillis = prompt.intervalSeconds() * 1000L;
        while (true) {
            if (Instant.now().isAfter(prompt.expiresAt())) {
                throw new MsaAuthException(MsaAuthException.Reason.DEVICE_CODE_EXPIRED, "The sign-in code expired.");
            }
            Thread.sleep(intervalMillis);

            HttpRequest request = tokenRequest(form);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (response.statusCode() == 200) {
                return tokensFrom(body);
            }
            String error = body.has("error") ? body.get("error").getAsString() : "unknown_error";
            switch (error) {
                case "authorization_pending" -> { /* keep polling */ }
                case "slow_down" -> intervalMillis += 5000;
                case "expired_token" ->
                        throw new MsaAuthException(MsaAuthException.Reason.DEVICE_CODE_EXPIRED, "The sign-in code expired.");
                case "authorization_declined" ->
                        throw new MsaAuthException(MsaAuthException.Reason.AUTHORIZATION_DECLINED, "Sign-in was declined.");
                default -> throw new MsaAuthException(MsaAuthException.Reason.UNKNOWN, "Microsoft sign-in error: " + error);
            }
        }
    }

    public MicrosoftTokens refreshToken(String clientId, String refreshToken) throws IOException, InterruptedException {
        String form = "grant_type=refresh_token&client_id=" + encode(clientId)
                + "&refresh_token=" + encode(refreshToken) + "&scope=" + encode(SCOPE);
        HttpRequest request = tokenRequest(form);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() != 200) {
            throw new MsaAuthException(MsaAuthException.Reason.REFRESH_TOKEN_INVALID, "Cached Microsoft sign-in is no longer valid.");
        }
        return tokensFrom(body);
    }

    private MicrosoftTokens tokensFrom(JsonObject body) {
        return new MicrosoftTokens(
                body.get("access_token").getAsString(),
                body.get("refresh_token").getAsString(),
                Instant.now().plusSeconds(body.get("expires_in").getAsLong()));
    }

    private HttpRequest tokenRequest(String form) {
        return HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
    }

    private JsonObject postForm(String url, String form) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Microsoft device-code request failed: HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
