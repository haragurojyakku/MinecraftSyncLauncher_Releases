package dev.haraguro.modserverplaymanager.launcher.launch;

import dev.haraguro.modserverplaymanager.launcher.auth.MsaAuthException;
import dev.haraguro.modserverplaymanager.launcher.i18n.Messages;

import java.util.Locale;

/** Shared by the console and GUI front ends so both report failures the same way. */
public final class ErrorMessages {

    private ErrorMessages() {
    }

    /** Some exceptions (e.g. ConnectException) have a null/unhelpful getMessage(). */
    public static String describe(Throwable e) {
        String message = e.getMessage();
        String description = (message != null && !message.isBlank()) ? message : e.getClass().getSimpleName();
        if (e instanceof MsaAuthException msa) {
            return Messages.get("error.msa." + msa.reason().name().toLowerCase(Locale.ROOT) + ".hint", description);
        }
        if (e instanceof java.net.ConnectException) {
            return Messages.get("error.connectException.hint", description);
        }
        return description;
    }
}
