package dev.haraguro.modserverplaymanager.launcher.launch;

import dev.haraguro.modserverplaymanager.launcher.config.LauncherConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns a resolved profile + auth session into the actual `java ...` argv. */
public class LaunchCommandBuilder {

    public List<String> build(MinecraftProfile profile, Path installDir, AuthSession auth, LauncherConfig config) {
        Path nativesDir = installDir.resolve("natives").resolve(profile.id());

        String classpath = buildClasspath(profile, installDir);
        Map<String, String> vars = placeholderValues(profile, installDir, nativesDir, auth, classpath, config);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xms" + config.getMinMemoryMb() + "M");
        command.add("-Xmx" + config.getMaxMemoryMb() + "M");
        // So the in-game mod's ApiClient (see SyncMod) actually reaches this launcher's configured
        // sync-server instead of falling back to its hardcoded localhost:7070 default.
        command.add("-Dmcsync.api.baseUrl=" + config.getApiBaseUrl());
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            command.add("-Dmcsync.api.key=" + config.getApiKey());
        }
        for (String jvmArg : profile.jvmArgTemplates()) {
            command.add(substitute(jvmArg, vars));
        }
        command.add(profile.mainClass());
        for (String gameArg : profile.gameArgTemplates()) {
            command.add(substitute(gameArg, vars));
        }
        return command;
    }

    private String buildClasspath(MinecraftProfile profile, Path installDir) {
        String separator = System.getProperty("path.separator");
        List<String> entries = new ArrayList<>();
        for (ResolvedLibrary library : profile.libraries()) {
            entries.add(installDir.resolve(library.relativePath()).toString());
        }
        entries.add(installDir.resolve(profile.clientJar().relativePath()).toString());
        return entries.stream().collect(Collectors.joining(separator));
    }

    private Map<String, String> placeholderValues(MinecraftProfile profile, Path installDir, Path nativesDir,
                                                    AuthSession auth, String classpath, LauncherConfig config) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("auth_player_name", auth.playerName());
        vars.put("version_name", profile.id());
        vars.put("game_directory", installDir.toString());
        vars.put("assets_root", installDir.resolve("assets").toString());
        vars.put("assets_index_name", profile.assetIndex().id());
        vars.put("auth_uuid", auth.uuid().toString());
        vars.put("auth_access_token", auth.accessToken());
        vars.put("auth_xuid", auth.xuid());
        vars.put("clientid", "mcsync-launcher");
        vars.put("user_type", auth.userType());
        vars.put("version_type", "release");
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", "mcsync-launcher");
        vars.put("launcher_version", "0.1.0");
        vars.put("classpath", classpath);
        if (config.isAutoConnectEnabled()) {
            vars.put("quickPlayMultiplayer", config.getServerAddress());
        }
        return vars;
    }

    private String substitute(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String javaExecutable() {
        String exeName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exeName).toString();
    }
}
