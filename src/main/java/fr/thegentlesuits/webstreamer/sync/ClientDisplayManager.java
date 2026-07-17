package fr.thegentlesuits.webstreamer.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.thegentlesuits.webstreamer.WebStreamerClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side display registry, fed exclusively by the server through the
 * {@code webstreamer:display_sync} channel (see webstreamer-bridge-protocol.md).
 */
@Environment(EnvType.CLIENT)
public class ClientDisplayManager {

    private static final byte PROTOCOL_VERSION = 1;
    private static final byte OP_FULL = 0;
    private static final byte OP_UPSERT = 1;
    private static final byte OP_REMOVE = 2;

    private final Map<BlockPos, ClientDisplay> displays = new ConcurrentHashMap<>();

    /** Level the current display set was synced in — payloads can arrive before its first frame renders. */
    private volatile ClientLevel syncedLevel;

    public Collection<ClientDisplay> all() {
        return this.displays.values();
    }

    public boolean isEmpty() {
        return this.displays.isEmpty();
    }

    public void clear() {
        this.displays.clear();
        this.syncedLevel = null;
    }

    /**
     * Drops the display set if it was synced in another level than {@code current}
     * (dimension change without a fresh FULL yet). A set synced in {@code current}
     * is kept even if that level has not rendered a frame yet.
     */
    public void clearIfStale(ClientLevel current) {
        if (this.syncedLevel != current && !this.displays.isEmpty()) {
            this.displays.clear();
        }
    }

    /** Decodes one message from the sync channel. Runs on the client main thread. */
    public void handle(byte[] data) {

        this.syncedLevel = Minecraft.getInstance().level;

        if (data.length < 2) {
            WebStreamerClient.LOGGER.warn("Ignoring truncated display sync payload ({} bytes).", data.length);
            return;
        }
        if (data[0] != PROTOCOL_VERSION) {
            WebStreamerClient.LOGGER.warn("Ignoring display sync payload with unsupported protocol version {}.", data[0]);
            return;
        }

        byte op = data[1];
        JsonObject body;
        try {
            String json = new String(data, 2, data.length - 2, StandardCharsets.UTF_8);
            body = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            WebStreamerClient.LOGGER.warn("Ignoring malformed display sync payload.", e);
            return;
        }

        try {
            switch (op) {
                case OP_FULL -> {
                    if (body.get("reset").getAsBoolean()) {
                        this.displays.clear();
                    }
                    JsonArray array = body.getAsJsonArray("displays");
                    for (JsonElement element : array) {
                        this.put(ClientDisplay.fromJson(element.getAsJsonObject()));
                    }
                    WebStreamerClient.LOGGER.info("Display sync: now tracking {} display(s).", this.displays.size());
                }
                case OP_UPSERT -> this.put(ClientDisplay.fromJson(body.getAsJsonObject("display")));
                case OP_REMOVE -> {
                    BlockPos pos = new BlockPos(
                            body.get("x").getAsInt(),
                            body.get("y").getAsInt(),
                            body.get("z").getAsInt());
                    this.displays.remove(pos);
                }
                default -> WebStreamerClient.LOGGER.warn("Ignoring display sync payload with unknown op {}.", op);
            }
        } catch (Exception e) {
            WebStreamerClient.LOGGER.warn("Failed to apply display sync payload.", e);
        }

    }

    private void put(ClientDisplay display) {
        this.displays.put(display.pos, display);
    }

}
