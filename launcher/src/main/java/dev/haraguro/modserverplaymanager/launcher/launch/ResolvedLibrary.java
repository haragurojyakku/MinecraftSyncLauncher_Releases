package dev.haraguro.modserverplaymanager.launcher.launch;

/**
 * A single classpath jar (Minecraft library, or the client jar itself),
 * already reduced to "where do I get it / where does it live locally".
 */
public record ResolvedLibrary(String relativePath, String url, String sha1, long size) {
}
