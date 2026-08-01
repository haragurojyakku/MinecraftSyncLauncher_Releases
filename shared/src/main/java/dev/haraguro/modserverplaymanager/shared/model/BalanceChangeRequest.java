package dev.haraguro.modserverplaymanager.shared.model;

/**
 * Body for {@code Routes.PLAYER_BALANCE_DEPOSIT}/{@code PLAYER_BALANCE_WITHDRAW}.
 * Amount is always positive; the route decides the sign.
 */
public class BalanceChangeRequest {

    private double amount;

    public BalanceChangeRequest() {
    }

    public BalanceChangeRequest(double amount) {
        this.amount = amount;
    }

    public double getAmount() {
        return amount;
    }
}
