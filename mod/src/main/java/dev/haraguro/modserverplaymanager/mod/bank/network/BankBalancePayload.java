package dev.haraguro.modserverplaymanager.mod.bank.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C — pushes the player's current bank balance, e.g. to refresh the ender chest bank panel. */
public record BankBalancePayload(double balance) implements CustomPacketPayload {

    // createType(String) treats its whole argument as a bare path under the "minecraft"
    // namespace (it does NOT split on ':'), so a custom namespace needs the Type
    // constructor + an explicit Identifier instead — see BankNetworking's crash log.
    public static final CustomPacketPayload.Type<BankBalancePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mcsync-mod", "bank_balance"));

    public static final StreamCodec<FriendlyByteBuf, BankBalancePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> buf.writeDouble(payload.balance()),
            buf -> new BankBalancePayload(buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
