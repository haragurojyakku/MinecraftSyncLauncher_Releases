package dev.haraguro.modserverplaymanager.mod.bank;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import dev.haraguro.modserverplaymanager.mod.SyncMod;
import dev.haraguro.modserverplaymanager.mod.api.ApiClient;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /bank balance} and {@code /bank pay <player> <amount>} — see ApiClient's balance endpoints. */
public final class BankCommands {

    private BankCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                dispatcher.register(Commands.literal("bank")
                        .then(Commands.literal("balance")
                                .executes(BankCommands::balance))
                        .then(Commands.literal("pay")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                                .executes(BankCommands::pay))))));
    }

    private static int balance(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ApiClient apiClient = SyncMod.apiClient();
        if (apiClient == null) {
            ctx.getSource().sendFailure(Component.translatable("mcsync.bank.unavailable"));
            return 0;
        }
        apiClient.fetchPlayerProfileAsync(sender.getUUID()).whenComplete((profile, error) ->
                sender.level().getServer().execute(() -> {
                    if (error != null) {
                        sender.sendSystemMessage(Component.translatable("mcsync.bank.unavailable"));
                        SyncMod.LOGGER.warn("Failed to fetch balance for {}: {}", sender.getGameProfile().name(), error.toString());
                        return;
                    }
                    sender.sendSystemMessage(Component.translatable("mcsync.bank.balance", profile.get("balance").getAsDouble()));
                }));
        return 1;
    }

    private static int pay(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");

        if (target.getUUID().equals(sender.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable("mcsync.bank.pay_self"));
            return 0;
        }
        ApiClient apiClient = SyncMod.apiClient();
        if (apiClient == null) {
            ctx.getSource().sendFailure(Component.translatable("mcsync.bank.unavailable"));
            return 0;
        }

        apiClient.transferAsync(sender.getUUID(), target.getUUID(), amount).whenComplete((profile, error) ->
                sender.level().getServer().execute(() -> {
                    if (error != null) {
                        if (error.getCause() instanceof ApiClient.InsufficientBalanceException) {
                            sender.sendSystemMessage(Component.translatable("mcsync.bank.insufficient_balance"));
                        } else {
                            sender.sendSystemMessage(Component.translatable("mcsync.bank.unavailable"));
                            SyncMod.LOGGER.warn("Transfer failed from {} to {}: {}",
                                    sender.getGameProfile().name(), target.getGameProfile().name(), error.toString());
                        }
                        return;
                    }
                    sender.sendSystemMessage(Component.translatable("mcsync.bank.pay_sent", amount, target.getGameProfile().name()));
                    target.sendSystemMessage(Component.translatable("mcsync.bank.pay_received", amount, sender.getGameProfile().name()));
                }));
        return 1;
    }
}
