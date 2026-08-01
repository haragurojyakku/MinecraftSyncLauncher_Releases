package dev.haraguro.modserverplaymanager.mod.bank.client;

import dev.haraguro.modserverplaymanager.mod.bank.network.BankBalanceRequestPayload;
import dev.haraguro.modserverplaymanager.mod.bank.network.BankDepositRequestPayload;
import dev.haraguro.modserverplaymanager.mod.bank.network.BankWithdrawRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Opened from an added "Bank" button on the player's ender chest screen (see
 * EnderChestBankMixin) — converts held emeralds into balance and back, at a
 * fixed 1:1 rate. The actual inventory/balance mutation happens server-side
 * (BankNetworking); this just sends the player's requested amount and shows
 * whatever balance the server reports back.
 */
public final class BankPanelScreen extends Screen {

    private static final int WIDTH = 240;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;

    private Button balanceLabel;
    private Button statusLabel;
    private EditBox amountBox;

    public BankPanelScreen(Screen parent) {
        super(Component.translatable("mcsync.bank.panel.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2 - WIDTH / 2;
        int y = height / 2 - 70;

        balanceLabel = Button.builder(Component.translatable("mcsync.bank.panel.loading"), b -> {
        }).bounds(centerX, y, WIDTH, BUTTON_HEIGHT).build();
        balanceLabel.active = false;
        addRenderableWidget(balanceLabel);
        y += BUTTON_HEIGHT + 8;

        amountBox = new EditBox(font, centerX, y, WIDTH, BUTTON_HEIGHT, Component.translatable("mcsync.bank.panel.amount"));
        amountBox.setMaxLength(9);
        amountBox.setValue("1");
        addRenderableWidget(amountBox);
        setInitialFocus(amountBox);
        y += BUTTON_HEIGHT + 8;

        addRenderableWidget(Button.builder(Component.translatable("mcsync.bank.panel.deposit"), b -> send(true))
                .bounds(centerX, y, WIDTH / 2 - 4, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("mcsync.bank.panel.withdraw"), b -> send(false))
                .bounds(centerX + WIDTH / 2 + 4, y, WIDTH / 2 - 4, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + 8;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            if (minecraft != null) {
                minecraft.setScreenAndShow(parent);
            }
        }).bounds(centerX, y, WIDTH, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + 8;

        statusLabel = Button.builder(Component.empty(), b -> {
        }).bounds(centerX, y, WIDTH, BUTTON_HEIGHT).build();
        statusLabel.active = false;
        statusLabel.visible = false;
        addRenderableWidget(statusLabel);

        ClientPlayNetworking.send(new BankBalanceRequestPayload());
    }

    private void send(boolean deposit) {
        int amount;
        try {
            amount = Integer.parseInt(amountBox.getValue().trim());
        } catch (NumberFormatException e) {
            showStatus(Component.translatable("mcsync.bank.panel.invalid_amount"));
            return;
        }
        if (amount <= 0) {
            showStatus(Component.translatable("mcsync.bank.panel.invalid_amount"));
            return;
        }
        statusLabel.visible = false;
        if (deposit) {
            ClientPlayNetworking.send(new BankDepositRequestPayload(amount));
        } else {
            ClientPlayNetworking.send(new BankWithdrawRequestPayload(amount));
        }
    }

    private void showStatus(Component message) {
        statusLabel.setMessage(message);
        statusLabel.visible = true;
    }

    /** Called by the client-side S2C receiver (see SyncModClient) whenever this screen is open. */
    public void onBalanceUpdate(double balance) {
        if (balanceLabel != null) {
            balanceLabel.setMessage(Component.translatable("mcsync.bank.balance", balance));
        }
        if (statusLabel != null) {
            statusLabel.visible = false;
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
        }
    }
}
