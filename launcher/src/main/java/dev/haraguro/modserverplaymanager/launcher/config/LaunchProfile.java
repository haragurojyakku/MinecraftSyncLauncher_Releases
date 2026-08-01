package dev.haraguro.modserverplaymanager.launcher.config;

/**
 * One selectable JVM memory preset — also carries its own offline player name, so switching
 * profiles (e.g. different family members sharing this PC) switches the player name along with
 * the memory settings. Add more by hand-editing launcher-config.json.
 */
public record LaunchProfile(String name, String playerName, int minMemoryMb, int maxMemoryMb) {
    public static final LaunchProfile DEFAULT = new LaunchProfile("Standard", "", 1024, 4096);
}
