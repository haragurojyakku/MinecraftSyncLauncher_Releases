package dev.haraguro.modserverplaymanager.launcher.launch;

import java.util.List;

/**
 * The fully resolved launch profile: vanilla 26.2's version JSON with
 * Fabric's loader profile JSON merged on top (mainClass replaced, libraries
 * and JVM args appended — same inheritsFrom merge semantics real launchers
 * use for modded profiles). Argument placeholders like ${auth_player_name}
 * are left unresolved here; LaunchCommandBuilder substitutes them.
 */
public record MinecraftProfile(
        String id,
        String mainClass,
        List<String> gameArgTemplates,
        List<String> jvmArgTemplates,
        List<ResolvedLibrary> libraries,
        ResolvedLibrary clientJar,
        AssetIndexRef assetIndex,
        int javaMajorVersion
) {
    public record AssetIndexRef(String id, String url, String sha1, long size) {
    }
}
