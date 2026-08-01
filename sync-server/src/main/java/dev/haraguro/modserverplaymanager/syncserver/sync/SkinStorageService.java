package dev.haraguro.modserverplaymanager.syncserver.sync;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes server/player-skins/&lt;uuid&gt;.png — this project's own
 * skin storage, independent of Mojang's skin servers (irrelevant here since
 * the server runs online-mode=false, see OfflineAuth). One file per player,
 * always overwritten on re-upload (unlike ServerFilesService's mods/
 * resourcepacks, a skin has no "disabled" state and no name collisions to
 * guard against — the UUID *is* the identity).
 */
public class SkinStorageService {

    private static final int SKIN_WIDTH = 64;

    private final Path skinsDir;

    public SkinStorageService(Path serverDir) {
        this.skinsDir = serverDir.resolve("player-skins");
    }

    public Optional<Path> resolveSkin(UUID uuid) {
        Path path = skinsDir.resolve(fileName(uuid));
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    public Optional<String> sha1(UUID uuid) {
        Optional<Path> path = resolveSkin(uuid);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(sha1(Files.readAllBytes(path.get())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @throws IllegalArgumentException if the upload isn't a readable PNG, or isn't a
     *         standard 64x64 or legacy 64x32 skin image
     */
    public void saveSkin(UUID uuid, InputStream content) throws IOException {
        byte[] bytes = content.readAllBytes();
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a readable image file: " + e.getMessage());
        }
        if (image == null) {
            throw new IllegalArgumentException("Not a readable PNG image");
        }
        if (image.getWidth() != SKIN_WIDTH || (image.getHeight() != 64 && image.getHeight() != 32)) {
            throw new IllegalArgumentException(
                    "Skin must be a " + SKIN_WIDTH + "x64 (or legacy 64x32) PNG, got "
                            + image.getWidth() + "x" + image.getHeight());
        }

        Files.createDirectories(skinsDir);
        Files.write(skinsDir.resolve(fileName(uuid)), bytes,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String fileName(UUID uuid) {
        return uuid + ".png";
    }

    private static String sha1(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
