package dev.haraguro.modserverplaymanager.launcher.update;

/**
 * The launcher's own version, read from the jar manifest's
 * Implementation-Version (set in launcher/build.gradle.kts from
 * gradle.properties' `version`). Only populated when running from the
 * built jar — `:launcher:run`'s loose classes have no manifest, so this
 * returns "dev" and {@link UpdateChecker} treats that as "never newer,
 * don't bother checking."
 */
public final class AppVersion {

    private AppVersion() {
    }

    public static String current() {
        String version = AppVersion.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
