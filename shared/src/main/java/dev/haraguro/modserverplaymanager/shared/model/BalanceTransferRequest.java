package dev.haraguro.modserverplaymanager.shared.model;

import java.util.UUID;

/** Body for {@code Routes.PLAYER_BALANCE_TRANSFER} — sender is the {uuid} path param. */
public class BalanceTransferRequest {

    private UUID toUuid;
    private double amount;

    public BalanceTransferRequest() {
    }

    public BalanceTransferRequest(UUID toUuid, double amount) {
        this.toUuid = toUuid;
        this.amount = amount;
    }

    public UUID getToUuid() {
        return toUuid;
    }

    public double getAmount() {
        return amount;
    }
}
