package dev.haraguro.modserverplaymanager.launcher.launch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Downloads a file to a path if it's missing or doesn't match the expected sha1/size. */
public class FileDownloader {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void downloadIfMissing(Path target, String url, String sha1, long size) throws IOException, InterruptedException {
        if (isUpToDate(target, sha1, size)) {
            return;
        }
        Files.createDirectories(target.getParent());

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download " + url + ": HTTP " + response.statusCode());
        }
        Files.write(target, response.body());
    }

    private boolean isUpToDate(Path target, String sha1, long size) throws IOException {
        if (!Files.exists(target)) {
            return false;
        }
        if (size >= 0 && Files.size(target) != size) {
            return false;
        }
        if (sha1 == null) {
            return size >= 0; // nothing to verify against beyond size; assume present == fine
        }
        return sha1.equalsIgnoreCase(sha1Of(target));
    }

    private static String sha1Of(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
