package dev.haraguro.modserverplaymanager.mod.bank.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S — asks the server for the current balance (sent when the ender chest bank panel opens). */
public record BankBalanceRequestPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BankBalanceRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mcsync-mod", "bank_balance_request"));

    public static final StreamCodec<FriendlyByteBuf, BankBalanceRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new BankBalanceRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
