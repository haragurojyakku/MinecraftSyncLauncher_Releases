package dev.haraguro.modserverplaymanager.mod.bank.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S — from the ender chest bank panel: convert {@code amount} of balance
 * back into physical emeralds. The server re-checks the player's actual
 * balance and available inventory space before granting anything.
 */
public record BankWithdrawRequestPayload(int amount) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BankWithdrawRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mcsync-mod", "bank_withdraw_request"));

    public static final StreamCodec<FriendlyByteBuf, BankWithdrawRequestPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> ByteBufCodecs.VAR_INT.encode(buf, payload.amount()),
            buf -> new BankWithdrawRequestPayload(ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
