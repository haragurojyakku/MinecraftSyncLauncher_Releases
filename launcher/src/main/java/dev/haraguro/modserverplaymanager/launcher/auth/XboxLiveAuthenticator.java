package dev.haraguro.modserverplaymanager.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Xbox Live authenticate + XSTS authorize — the two steps between a Microsoft token and a Minecraft-services-ready one. */
public class XboxLiveAuthenticator {

    private static final String XBL_AUTHENTICATE_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTHORIZE_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT_RELYING_PARTY = "rp://api.minecraftservices.com/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record XboxLiveResult(String token, String userHash) {
    }

    public record XstsResult(String token, String userHash, String xuid) {
    }

    public XboxLiveResult authenticate(String msAccessToken) throws IOException, InterruptedException {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + msAccessToken);

        JsonObject body = new JsonObject();
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        JsonObject response = post(XBL_AUTHENTICATE_URL, body);
        return new XboxLiveResult(response.get("Token").getAsString(), userHashFrom(response));
    }

    public XstsResult authorizeForMinecraft(String xblToken) throws IOException, InterruptedException {
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);

        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);

        JsonObject body = new JsonObject();
        body.add("Properties", properties);
        body.addProperty("RelyingParty", MINECRAFT_RELYING_PARTY);
        body.addProperty("TokenType", "JWT");

        HttpRequest request = jsonRequest(XSTS_AUTHORIZE_URL, body);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (response.statusCode() == 401) {
            throw xstsError(json);
        }
        if (response.statusCode() != 200) {
            throw new IOException("XSTS authorize failed: HTTP " + response.statusCode());
        }

        JsonObject xui = json.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject();
        return new XstsResult(json.get("Token").getAsString(), xui.get("uhs").getAsString(), xui.get("xid").getAsString());
    }

    /** Known community-documented XErr codes; anything else surfaces as UNKNOWN with the raw code for debugging. */
    private MsaAuthException xstsError(JsonObject json) {
        long errCode = json.has("XErr") ? json.get("XErr").getAsLong() : -1;
        if (errCode == 2148916233L) {
            return new MsaAuthException(MsaAuthException.Reason.NO_XBOX_ACCOUNT,
                    "This Microsoft account has no Xbox profile.");
        }
        if (errCode == 2148916235L) {
            return new MsaAuthException(MsaAuthException.Reason.BANNED_REGION,
                    "Xbox Live isn't available for this account's region.");
        }
        if (errCode == 2148916236L || errCode == 2148916237L) {
            return new MsaAuthException(MsaAuthException.Reason.CHILD_ACCOUNT,
                    "This account needs adult permission enabled in family settings.");
        }
        return new MsaAuthException(MsaAuthException.Reason.UNKNOWN, "XSTS authorize failed: XErr " + errCode);
    }

    private String userHashFrom(JsonObject response) {
        return response.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0)
                .getAsJsonObject().get("uhs").getAsString();
    }

    private JsonObject post(String url, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = jsonRequest(url, body);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Xbox Live authenticate failed: HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private HttpRequest jsonRequest(String url, JsonObject body) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }
}
