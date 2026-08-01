package dev.haraguro.modserverplaymanager.launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads whatever a {@link MinecraftProfile} needs into the launcher's
 * install dir: the client jar + libraries (classpath), and separately the
 * asset index + objects (textures/sounds/lang — hundreds of MB, so it's
 * kept as its own step rather than bundled into every launch check).
 */
public class GameInstaller {

    private static final String ASSETS_BASE_URL = "https://resources.download.minecraft.net/";
    private static final int MAX_CONCURRENT_DOWNLOADS = 16;

    private final Path installDir;
    private final FileDownloader downloader = new FileDownloader();

    public GameInstaller(Path installDir) {
        this.installDir = installDir;
    }

    /** Client jar + full classpath. Required before every launch. */
    public void ensureInstalled(MinecraftProfile profile, ProgressListener listener) throws InterruptedException, IOException {
        List<ResolvedLibrary> toFetch = new ArrayList<>(profile.libraries());
        toFetch.add(profile.clientJar());
        downloadAll(toFetch, listener);
    }

    /**
     * Textures/sounds/lang files. Can be gigabytes on first run — call this
     * once up front, not on every launch, and expect it to take a while.
     */
    public void ensureAssets(MinecraftProfile profile, ProgressListener listener) throws IOException, InterruptedException {
        MinecraftProfile.AssetIndexRef ref = profile.assetIndex();
        Path indexPath = installDir.resolve("assets/indexes/" + ref.id() + ".json");
        downloader.downloadIfMissing(indexPath, ref.url(), ref.sha1(), ref.size());

        JsonObject index = JsonParser.parseString(Files.readString(indexPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject objects = index.getAsJsonObject("objects");

        List<ResolvedLibrary> assetFiles = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : objects.entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = object.get("hash").getAsString();
            long size = object.get("size").getAsLong();
            String prefix = hash.substring(0, 2);
            assetFiles.add(new ResolvedLibrary(
                    "assets/objects/" + prefix + "/" + hash,
                    ASSETS_BASE_URL + prefix + "/" + hash,
                    hash,
                    size));
        }
        downloadAll(assetFiles, listener);
    }

    private void downloadAll(List<ResolvedLibrary> files, ProgressListener listener) throws InterruptedException {
        AtomicInteger completed = new AtomicInteger(0);
        int total = files.size();
        try (ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS, Thread.ofVirtual().factory())) {
            List<Future<?>> futures = new ArrayList<>(files.size());
            for (ResolvedLibrary file : files) {
                futures.add(executor.submit(() -> {
                    try {
                        downloader.downloadIfMissing(installDir.resolve(file.relativePath()), file.url(), file.sha1(), file.size());
                        listener.onFileComplete(completed.incrementAndGet(), total);
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException("Failed to download " + file.relativePath(), e);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    throw new RuntimeException(e.getCause());
                }
            }
        }
    }
}
