package dev.haraguro.modserverplaymanager.launcher.auth;

import java.io.IOException;

/** Thrown at any stage of the Microsoft/Xbox/Minecraft sign-in chain, with a {@link Reason} so the GUI can show a specific message. */
public class MsaAuthException extends IOException {

    public enum Reason {
        CLIENT_ID_MISSING,
        DEVICE_CODE_EXPIRED,
        AUTHORIZATION_DECLINED,
        NO_XBOX_ACCOUNT,
        CHILD_ACCOUNT,
        BANNED_REGION,
        GAME_NOT_OWNED,
        REFRESH_TOKEN_INVALID,
        NETWORK,
        UNKNOWN
    }

    private final Reason reason;

    public MsaAuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
