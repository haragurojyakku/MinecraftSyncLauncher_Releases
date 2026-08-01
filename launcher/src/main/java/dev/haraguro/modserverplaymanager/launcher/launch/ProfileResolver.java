package dev.haraguro.modserverplaymanager.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link MinecraftProfile} the same way third-party launchers do
 * for a Fabric profile: fetch Mojang's vanilla version JSON, fetch Fabric's
 * ready-made loader profile JSON (which declares {@code inheritsFrom} the
 * vanilla version), and merge them — child's mainClass wins, libraries and
 * JVM args are concatenated (child first), game args are vanilla's (Fabric
 * doesn't add any today, but we still concatenate to stay correct if it
 * ever does).
 */
public class ProfileResolver {

    private static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_PROFILE_URL_TEMPLATE = "https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public MinecraftProfile resolve(String minecraftVersion, String fabricLoaderVersion) throws IOException, InterruptedException {
        return resolve(minecraftVersion, fabricLoaderVersion, Set.of());
    }

    public MinecraftProfile resolve(String minecraftVersion, String fabricLoaderVersion, Set<String> enabledFeatures)
            throws IOException, InterruptedException {
        JsonObject vanilla = fetchVanillaVersionJson(minecraftVersion);
        JsonObject fabric = fetchJson(FABRIC_PROFILE_URL_TEMPLATE.formatted(minecraftVersion, fabricLoaderVersion));

        String mainClass = fabric.get("mainClass").getAsString();

        List<ResolvedLibrary> libraries = mergeLibraries(
                parseFabricLibraries(fabric.getAsJsonArray("libraries")),
                parseVanillaLibraries(vanilla.getAsJsonArray("libraries")));

        List<String> gameArgs = new ArrayList<>();
        gameArgs.addAll(parseArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("game"), enabledFeatures));
        gameArgs.addAll(parseArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("game"), enabledFeatures));

        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.addAll(parseArguments(vanilla.getAsJsonObject("arguments").getAsJsonArray("jvm")));
        jvmArgs.addAll(parseArguments(fabric.getAsJsonObject("arguments").getAsJsonArray("jvm")));

        JsonObject clientDownload = vanilla.getAsJsonObject("downloads").getAsJsonObject("client");
        ResolvedLibrary clientJar = new ResolvedLibrary(
                "versions/" + minecraftVersion + "/" + minecraftVersion + ".jar",
                clientDownload.get("url").getAsString(),
                clientDownload.get("sha1").getAsString(),
                clientDownload.get("size").getAsLong());

        JsonObject assetIndexJson = vanilla.getAsJsonObject("assetIndex");
        MinecraftProfile.AssetIndexRef assetIndex = new MinecraftProfile.AssetIndexRef(
                assetIndexJson.get("id").getAsString(),
                assetIndexJson.get("url").getAsString(),
                assetIndexJson.get("sha1").getAsString(),
                assetIndexJson.get("size").getAsLong());

        int javaMajorVersion = vanilla.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();

        return new MinecraftProfile(fabric.get("id").getAsString(), mainClass, gameArgs, jvmArgs,
                libraries, clientJar, assetIndex, javaMajorVersion);
    }

    private JsonObject fetchVanillaVersionJson(String minecraftVersion) throws IOException, InterruptedException {
        JsonObject manifest = fetchJson(VERSION_MANIFEST_URL);
        for (JsonElement element : manifest.getAsJsonArray("versions")) {
            JsonObject version = element.getAsJsonObject();
            if (version.get("id").getAsString().equals(minecraftVersion)) {
                return fetchJson(version.get("url").getAsString());
            }
        }
        throw new IOException("Minecraft version not found in version manifest: " + minecraftVersion);
    }

    private JsonObject fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch " + url + ": HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private List<ResolvedLibrary> parseVanillaLibraries(JsonArray libraries) {
        List<ResolvedLibrary> result = new ArrayList<>();
        for (JsonElement element : libraries) {
            JsonObject lib = element.getAsJsonObject();
            if (lib.has("rules") && !Rules.allows(lib.getAsJsonArray("rules"))) {
                continue;
            }
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads == null || !downloads.has("artifact")) {
                continue; // no platform-independent artifact to fetch (e.g. natives-only entries)
            }
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            result.add(new ResolvedLibrary(
                    "libraries/" + artifact.get("path").getAsString(),
                    artifact.get("url").getAsString(),
                    artifact.get("sha1").getAsString(),
                    artifact.get("size").getAsLong()));
        }
        return result;
    }

    private List<ResolvedLibrary> parseFabricLibraries(JsonArray libraries) {
        List<ResolvedLibrary> result = new ArrayList<>();
        for (JsonElement element : libraries) {
            JsonObject lib = element.getAsJsonObject();
            String[] coords = lib.get("name").getAsString().split(":", 3);
            String group = coords[0];
            String artifact = coords[1];
            String version = coords[2];
            String path = "%s/%s/%s/%s-%s.jar".formatted(group.replace('.', '/'), artifact, version, artifact, version);
            String baseUrl = lib.get("url").getAsString();
            String downloadUrl = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + path;
            String sha1 = lib.has("sha1") ? lib.get("sha1").getAsString() : null;
            long size = lib.has("size") ? lib.get("size").getAsLong() : -1;
            result.add(new ResolvedLibrary("libraries/" + path, downloadUrl, sha1, size));
        }
        return result;
    }

    /** Child (Fabric) libraries take priority in the classpath and shadow same-path vanilla ones. */
    private List<ResolvedLibrary> mergeLibraries(List<ResolvedLibrary> child, List<ResolvedLibrary> parent) {
        Map<String, ResolvedLibrary> byPath = new LinkedHashMap<>();
        for (ResolvedLibrary lib : child) {
            byPath.put(lib.relativePath(), lib);
        }
        for (ResolvedLibrary lib : parent) {
            byPath.putIfAbsent(lib.relativePath(), lib);
        }
        return new ArrayList<>(byPath.values());
    }

    private List<String> parseArguments(JsonArray arguments) {
        return parseArguments(arguments, Set.of());
    }

    private List<String> parseArguments(JsonArray arguments, Set<String> enabledFeatures) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : arguments) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
                continue;
            }
            JsonObject conditional = element.getAsJsonObject();
            if (!Rules.allows(conditional.getAsJsonArray("rules"), enabledFeatures)) {
                continue;
            }
            JsonElement value = conditional.get("value");
            if (value.isJsonArray()) {
                for (JsonElement v : value.getAsJsonArray()) {
                    result.add(v.getAsString());
                }
            } else {
                result.add(value.getAsString());
            }
        }
        return result;
    }
}
