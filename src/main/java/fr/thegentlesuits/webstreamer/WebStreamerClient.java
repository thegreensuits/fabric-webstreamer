package fr.thegentlesuits.webstreamer;

import com.mojang.logging.LogUtils;
import fr.thegentlesuits.webstreamer.net.DisplaySyncPayload;
import fr.thegentlesuits.webstreamer.render.DisplayLayerManager;
import fr.thegentlesuits.webstreamer.render.DisplayRenderer;
import fr.thegentlesuits.webstreamer.sync.ClientDisplayManager;
import fr.thegentlesuits.webstreamer.twitch.TwitchClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.slf4j.Logger;

/**
 * WebStreamer (TheGentleSuits remake): pure client-side virtual displays.
 * The mod registers no blocks/items — it renders Twitch/HLS streams at world
 * coordinates received from the ViewingParty Paper plugin over the
 * {@code webstreamer:display_sync} channel. Registering the receiver announces
 * the channel to the server, which is the whole handshake.
 *
 * <p>Video/audio pipeline adapted from fabric-webstreamer by Theo Rozier (LGPL-3.0).</p>
 */
@Environment(EnvType.CLIENT)
public class WebStreamerClient implements ClientModInitializer {

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final ClientDisplayManager DISPLAYS = new ClientDisplayManager();
    public static final DisplayLayerManager LAYERS = new DisplayLayerManager();
    public static final TwitchClient TWITCH = new TwitchClient();

    @Override
    public void onInitializeClient() {

        PayloadTypeRegistry.clientboundPlay().register(DisplaySyncPayload.TYPE, DisplaySyncPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(DisplaySyncPayload.TYPE,
                (payload, context) -> DISPLAYS.handle(payload.data()));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DISPLAYS.clear();
            // DISCONNECT fires on the netty thread; GPU/AL resources must be freed
            // on the client thread.
            client.execute(LAYERS::cleanupAll);
        });

        DisplayRenderer renderer = new DisplayRenderer();
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::render);

        // Errors only: quiet enough for play, loud enough to diagnose broken streams.
        FFmpegLogCallback.set();
        FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR);

        LOGGER.info("WebStreamer client ready (display_sync protocol v1).");

    }

}
