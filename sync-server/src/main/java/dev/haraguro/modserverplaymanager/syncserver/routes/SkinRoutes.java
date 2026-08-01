package dev.haraguro.modserverplaymanager.syncserver.routes;

import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import dev.haraguro.modserverplaymanager.syncserver.sync.SkinStorageService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-player custom skin upload/download — see SkinStorageService. Player
 * data (not skins) lives in PlayerRoutes; kept separate since skins are a
 * binary blob with a different lifecycle (always-overwrite, no "disabled"
 * state) rather than a simple profile field.
 */
public class SkinRoutes {

    private final SkinStorageService skinStorage;

    public SkinRoutes(SkinStorageService skinStorage) {
        this.skinStorage = skinStorage;
    }

    public void register(Javalin app) {
        app.get(Routes.PLAYER_SKIN_HASH, ctx -> {
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            if (uuid == null) {
                return;
            }
            Optional<String> sha1 = skinStorage.sha1(uuid);
            if (sha1.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.json(Map.of("sha1", sha1.get()));
        });

        app.get(Routes.PLAYER_SKIN, ctx -> {
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            if (uuid == null) {
                return;
            }
            Optional<java.nio.file.Path> path = skinStorage.resolveSkin(uuid);
            if (path.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            try {
                ctx.contentType("image/png").result(Files.newInputStream(path.get()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        app.post(Routes.PLAYER_SKIN, ctx -> {
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            if (uuid == null) {
                return;
            }
            UploadedFile uploaded = ctx.uploadedFile("file");
            if (uploaded == null) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "missing multipart file part \"file\""));
                return;
            }
            try {
                skinStorage.saveSkin(uuid, uploaded.content());
                ctx.json(Map.of("sha1", skinStorage.sha1(uuid).orElseThrow()));
            } catch (IllegalArgumentException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
