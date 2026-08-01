package dev.haraguro.modserverplaymanager.mod.bank.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S — from the ender chest bank panel: convert {@code amount} emeralds
 * physically held by the player into balance. The server re-checks the
 * player actually has that many emeralds before touching their inventory.
 */
public record BankDepositRequestPayload(int amount) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BankDepositRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mcsync-mod", "bank_deposit_request"));

    public static final StreamCodec<FriendlyByteBuf, BankDepositRequestPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> ByteBufCodecs.VAR_INT.encode(buf, payload.amount()),
            buf -> new BankDepositRequestPayload(ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
