package dev.haraguro.modserverplaymanager.syncserver.routes;

import dev.haraguro.modserverplaymanager.syncserver.sync.ServerFilesService;
import dev.haraguro.modserverplaymanager.shared.model.ManagedFile;
import dev.haraguro.modserverplaymanager.shared.model.ModCategory;
import dev.haraguro.modserverplaymanager.shared.model.SyncServerSettings;
import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class SyncRoutes {

    // Keep in sync with the root project's gradle.properties; overridable so
    // ops can report the right values without a rebuild.
    private static final String MINECRAFT_VERSION = System.getProperty("mcsync.minecraft.version", "26.2");
    private static final String FABRIC_LOADER_VERSION = System.getProperty("mcsync.fabric.loader.version", "0.19.3");

    private final ServerFilesService filesService;

    public SyncRoutes(ServerFilesService filesService) {
        this.filesService = filesService;
    }

    public void register(Javalin app) {
        app.get(Routes.SYNC_MANIFEST, ctx ->
                ctx.json(filesService.buildManifest(MINECRAFT_VERSION, FABRIC_LOADER_VERSION)));

        app.get(Routes.SYNC_MODS_DOWNLOAD, ctx -> {
            String fileName = ctx.pathParam("file");
            serveOrNotFound(ctx, filesService.resolveModFile(fileName));
        });
        app.get(Routes.SYNC_RESOURCEPACKS_DOWNLOAD, ctx -> {
            String fileName = ctx.pathParam("file");
            serveOrNotFound(ctx, filesService.resolveResourcePackFile(fileName));
        });

        // --- Admin: list (incl. disabled), add, remove, enable/disable ---

        app.get(Routes.SYNC_MODS_LIST, ctx -> ctx.json(filesService.listManagedMods()));
        app.post(Routes.SYNC_MODS_LIST, ctx -> upload(ctx, filesService::addModFile));
        app.delete(Routes.SYNC_MODS_DOWNLOAD, ctx -> delete(ctx, filesService::deleteModFile));
        app.post(Routes.SYNC_MODS_TOGGLE, ctx -> toggle(ctx, filesService::toggleModFile));
        app.put(Routes.SYNC_MODS_CATEGORY, ctx -> {
            String fileName = ctx.pathParam("file");
            ModCategory category;
            try {
                category = ctx.bodyAsClass(CategoryUpdateRequest.class).category();
            } catch (RuntimeException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "invalid category"));
                return;
            }
            try {
                Optional<ManagedFile> result = filesService.setModCategory(fileName, category);
                if (result.isEmpty()) {
                    ctx.status(HttpStatus.NOT_FOUND);
                    return;
                }
                ctx.json(result.get());
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });

        app.get(Routes.SYNC_RESOURCEPACKS_LIST, ctx -> ctx.json(filesService.listManagedResourcePacks()));
        app.post(Routes.SYNC_RESOURCEPACKS_LIST, ctx -> upload(ctx, filesService::addResourcePackFile));
        app.delete(Routes.SYNC_RESOURCEPACKS_DOWNLOAD, ctx -> delete(ctx, filesService::deleteResourcePackFile));
        app.post(Routes.SYNC_RESOURCEPACKS_TOGGLE, ctx -> toggle(ctx, filesService::toggleResourcePackFile));

        app.get(Routes.SYNC_SETTINGS, ctx -> ctx.json(filesService.getSettings()));
        app.put(Routes.SYNC_SETTINGS, ctx -> {
            try {
                SyncServerSettings updated = filesService.updateSettings(ctx.bodyAsClass(SyncServerSettings.class));
                ctx.json(updated);
            } catch (IOException e) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
            }
        });
    }

    private void upload(Context ctx, FileAdder adder) {
        UploadedFile uploaded = ctx.uploadedFile("file");
        if (uploaded == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "missing multipart file part \"file\""));
            return;
        }
        try {
            ManagedFile result = adder.add(uploaded.filename(), uploaded.content());
            ctx.json(result);
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
        } catch (FileAlreadyExistsException e) {
            ctx.status(HttpStatus.CONFLICT).json(Map.of("error",
                    "A file named \"" + e.getFile() + "\" already exists on the server (enabled or disabled) — "
                            + "remove or rename it first before uploading a replacement."));
        } catch (IOException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
        }
    }

    private void delete(Context ctx, FileDeleter deleter) {
        String fileName = ctx.pathParam("file");
        try {
            boolean deleted = deleter.delete(fileName);
            if (!deleted) {
                ctx.status(HttpStatus.NOT_FOUND);
            }
        } catch (IOException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
        }
    }

    private void toggle(Context ctx, FileToggler toggler) {
        String fileName = ctx.pathParam("file");
        try {
            Optional<ManagedFile> result = toggler.toggle(fileName);
            if (result.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.json(result.get());
        } catch (IOException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", e.getMessage()));
        }
    }

    private static void serveOrNotFound(Context ctx, Optional<Path> file) {
        if (file.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            return;
        }
        try {
            ctx.result(Files.newInputStream(file.get()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface FileAdder {
        ManagedFile add(String fileName, java.io.InputStream content) throws IOException;
    }

    @FunctionalInterface
    private interface FileDeleter {
        boolean delete(String fileName) throws IOException;
    }

    @FunctionalInterface
    private interface FileToggler {
        Optional<ManagedFile> toggle(String fileName) throws IOException;
    }

    private record CategoryUpdateRequest(ModCategory category) {
    }
}
