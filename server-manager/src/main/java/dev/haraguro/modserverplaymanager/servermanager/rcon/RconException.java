package dev.haraguro.modserverplaymanager.servermanager.rcon;

import java.io.IOException;

/**
 * RCON connect/auth/protocol failure. Extends IOException so callers that
 * just want "did RCON work" can catch IOException broadly, while routes
 * that want to distinguish "server unreachable" (503) from other failures
 * can catch this specifically.
 */
public class RconException extends IOException {

    public RconException(String message) {
        super(message);
    }

    public RconException(String message, Throwable cause) {
        super(message, cause);
    }
}
