package dev.haraguro.modserverplaymanager.launcher.auth;

import dev.haraguro.modserverplaymanager.launcher.launch.AuthSession;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Orchestrates the full chain: Microsoft device-code/refresh -> Xbox Live
 * authenticate -> XSTS authorize -> Minecraft services login -> profile
 * fetch. Produces an {@link AuthSession} (userType "msa") plus the refresh
 * token the caller should persist for next time — Microsoft may rotate it
 * on every use, so the returned token isn't necessarily the one passed in.
 */
public class MinecraftAuthenticator {

    private final MicrosoftDeviceCodeAuth microsoft = new MicrosoftDeviceCodeAuth();
    private final XboxLiveAuthenticator xboxLive = new XboxLiveAuthenticator();
    private final MinecraftServicesAuthenticator minecraftServices = new MinecraftServicesAuthenticator();

    public record AuthResult(AuthSession session, String refreshToken) {
    }

    /** @param onCodeReady invoked with the user code/URL to show as soon as it's known, before this method blocks polling. */
    public AuthResult signInInteractive(String clientId, Consumer<MicrosoftDeviceCodeAuth.DeviceCodePrompt> onCodeReady)
            throws IOException, InterruptedException {
        requireClientId(clientId);
        MicrosoftDeviceCodeAuth.DeviceCodePrompt prompt = microsoft.requestDeviceCode(clientId);
        onCodeReady.accept(prompt);
        MicrosoftDeviceCodeAuth.MicrosoftTokens tokens = microsoft.pollForToken(clientId, prompt);
        return finishSignIn(tokens);
    }

    public AuthResult signInSilently(String clientId, String cachedRefreshToken) throws IOException, InterruptedException {
        requireClientId(clientId);
        MicrosoftDeviceCodeAuth.MicrosoftTokens tokens = microsoft.refreshToken(clientId, cachedRefreshToken);
        return finishSignIn(tokens);
    }

    private AuthResult finishSignIn(MicrosoftDeviceCodeAuth.MicrosoftTokens msTokens) throws IOException, InterruptedException {
        XboxLiveAuthenticator.XboxLiveResult xbl = xboxLive.authenticate(msTokens.accessToken());
        XboxLiveAuthenticator.XstsResult xsts = xboxLive.authorizeForMinecraft(xbl.token());
        String mcAccessToken = minecraftServices.loginWithXbox(xsts.userHash(), xsts.token());
        MinecraftServicesAuthenticator.MinecraftProfileInfo profile = minecraftServices.fetchProfile(mcAccessToken);

        AuthSession session = new AuthSession(profile.name(), profile.id(), mcAccessToken, "msa", xsts.xuid());
        return new AuthResult(session, msTokens.refreshToken());
    }

    private void requireClientId(String clientId) throws MsaAuthException {
        if (clientId == null || clientId.isBlank()) {
            throw new MsaAuthException(MsaAuthException.Reason.CLIENT_ID_MISSING, "No Microsoft Client ID configured.");
        }
    }
}
