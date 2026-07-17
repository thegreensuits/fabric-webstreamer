package fr.thegentlesuits.webstreamer.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Raw bridge payload from the ViewingParty Paper plugin: the body is
 * [version byte][op byte][UTF-8 JSON] as described in the bridge protocol doc.
 * Kept as raw bytes here; {@code ClientDisplayManager} does the decoding.
 */
public record DisplaySyncPayload(byte[] data) implements CustomPacketPayload {

    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("webstreamer", "display_sync");
    public static final CustomPacketPayload.Type<DisplaySyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);

    public static final StreamCodec<RegistryFriendlyByteBuf, DisplaySyncPayload> CODEC =
            CustomPacketPayload.codec(DisplaySyncPayload::write, DisplaySyncPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBytes(this.data);
    }

    private static DisplaySyncPayload read(RegistryFriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new DisplaySyncPayload(data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
