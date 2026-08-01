package dev.haraguro.modserverplaymanager.syncserver.routes;

import dev.haraguro.modserverplaymanager.shared.model.BalanceChangeRequest;
import dev.haraguro.modserverplaymanager.shared.model.BalanceTransferRequest;
import dev.haraguro.modserverplaymanager.shared.model.PlayerProfile;
import dev.haraguro.modserverplaymanager.shared.protocol.Routes;
import dev.haraguro.modserverplaymanager.syncserver.sync.PlayerAccountStore;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Player data + balance routes, backed by {@link PlayerAccountStore}
 * (file-persisted at {@code <serverDir>/.mcsync-players.json}).
 */
public class PlayerRoutes {

    private final PlayerAccountStore accounts;

    public PlayerRoutes(Path serverDir) {
        this.accounts = new PlayerAccountStore(serverDir);
    }

    public void register(Javalin app) {
        app.get(Routes.PLAYER_BY_UUID, ctx -> {
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            ctx.json(accounts.get(uuid));
        });

        app.post(Routes.PLAYER_BALANCE_DEPOSIT, ctx -> {
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            double amount = parseAmount(ctx.bodyAsClass(BalanceChangeRequest.class).getAmount());
            ctx.json(accounts.deposit(uuid, amount));
        });

        app.post(Routes.PLAYER_BALANCE_WITHDRAW, ctx -> {
            UUID uuid = parseUuid(ctx.pathParam("uuid"));
            double amount = parseAmount(ctx.bodyAsClass(BalanceChangeRequest.class).getAmount());
            PlayerProfile updated = withdraw(uuid, amount);
            ctx.json(updated);
        });

        app.post(Routes.PLAYER_BALANCE_TRANSFER, ctx -> {
            UUID fromUuid = parseUuid(ctx.pathParam("uuid"));
            BalanceTransferRequest body = ctx.bodyAsClass(BalanceTransferRequest.class);
            UUID toUuid = body.getToUuid();
            if (toUuid == null) {
                throw new BadRequestResponse("toUuid is required");
            }
            double amount = parseAmount(body.getAmount());
            try {
                accounts.transfer(fromUuid, toUuid, amount);
            } catch (IllegalArgumentException e) {
                throw new BadRequestResponse(e.getMessage());
            } catch (PlayerAccountStore.InsufficientBalanceException e) {
                throw new ConflictResponse(e.getMessage());
            }
            ctx.json(accounts.get(fromUuid));
        });
    }

    private PlayerProfile withdraw(UUID uuid, double amount) {
        try {
            return accounts.withdraw(uuid, amount);
        } catch (PlayerAccountStore.InsufficientBalanceException e) {
            throw new ConflictResponse(e.getMessage());
        }
    }

    private static double parseAmount(double amount) {
        if (!(amount > 0)) {
            throw new BadRequestResponse("amount must be positive: " + amount);
        }
        return amount;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("Invalid player UUID: " + raw);
        }
    }
}
