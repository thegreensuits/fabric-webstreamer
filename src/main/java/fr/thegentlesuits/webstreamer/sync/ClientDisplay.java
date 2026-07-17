package fr.thegentlesuits.webstreamer.sync;

import com.google.gson.JsonObject;
import fr.thegentlesuits.webstreamer.WebStreamerClient;
import fr.thegentlesuits.webstreamer.playlist.Playlist;
import fr.thegentlesuits.webstreamer.playlist.PlaylistQuality;
import fr.thegentlesuits.webstreamer.twitch.TwitchClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * One display as received from the server, plus the lazily resolved stream URI
 * (Twitch resolution is blocking, so it runs on the layer resources executor —
 * same idea as upstream fabric-webstreamer's DisplayRenderData).
 */
@Environment(EnvType.CLIENT)
public class ClientDisplay {

    public enum Attachment { WALL, FLOOR, CEILING }

    public final BlockPos pos;
    public final Direction facing;
    public final Attachment attachment;
    public final float width;
    public final float height;
    public final float audioDistance;
    public final float audioVolume;

    /** Twitch channel; empty or null means idle (unknown wire source types parse as idle). */
    public final String channel;
    public final String quality;

    private boolean sourceDirty = true;
    private Future<URI> futureUri;
    private URI uri;

    /** Streamers can retitle mid-stream; refresh the label this often while playing. */
    private static final long TITLE_REFRESH_INTERVAL_MS = 60_000;

    /** Stream title from Twitch, resolved together with the playlist. */
    private volatile String streamTitle;
    private volatile long titleFetchedAt;
    private final AtomicBoolean titleRefreshing = new AtomicBoolean();

    private ClientDisplay(BlockPos pos, Direction facing, Attachment attachment,
                          float width, float height, float audioDistance, float audioVolume,
                          String channel, String quality) {
        this.pos = pos;
        this.facing = facing;
        this.attachment = attachment;
        this.width = width;
        this.height = height;
        this.audioDistance = audioDistance;
        this.audioVolume = audioVolume;
        this.channel = channel;
        this.quality = quality;
    }

    /** Parses the wire Display object (see webstreamer-bridge-protocol.md). */
    public static ClientDisplay fromJson(JsonObject json) {
        BlockPos pos = new BlockPos(
                json.get("x").getAsInt(),
                json.get("y").getAsInt(),
                json.get("z").getAsInt());
        Direction facing = switch (json.get("facing").getAsString().toLowerCase(Locale.ROOT)) {
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.NORTH;
        };
        Attachment attachment = switch (json.get("attachment").getAsString().toLowerCase(Locale.ROOT)) {
            case "floor" -> Attachment.FLOOR;
            case "ceiling" -> Attachment.CEILING;
            default -> Attachment.WALL;
        };
        JsonObject source = json.getAsJsonObject("source");
        String type = source.get("type").getAsString().toLowerCase(Locale.ROOT);
        // Unknown source types (e.g. legacy "raw") parse as idle.
        String channel = "twitch".equals(type) && source.has("channel")
                ? source.get("channel").getAsString() : null;
        String quality = source.has("quality") ? source.get("quality").getAsString() : null;
        return new ClientDisplay(pos, facing, attachment,
                json.get("width").getAsFloat(),
                json.get("height").getAsFloat(),
                json.get("audioDistance").getAsFloat(),
                json.get("audioVolume").getAsFloat(),
                channel, quality);
    }

    /** True when the server configured an actual stream (protocol: empty channel = idle). */
    public boolean hasSource() {
        return this.channel != null && !this.channel.isEmpty();
    }

    /** Blocking resolution — runs on the resources executor, never on the render thread. */
    private URI resolveUri() {
        if (!this.hasSource()) {
            return null;
        }
        try {
            Playlist playlist = WebStreamerClient.TWITCH.requestPlaylist(this.channel);
            this.streamTitle = playlist.getTitle();
            this.titleFetchedAt = System.currentTimeMillis();
            WebStreamerClient.LOGGER.info("Resolved twitch channel {} (title: {}).",
                    this.channel, this.streamTitle);
            PlaylistQuality selected = playlist.getQuality(this.quality);
            if (selected == null) {
                // Requested quality unavailable: fall back to source quality.
                selected = playlist.getQuality("chunked");
            }
            if (selected == null && !playlist.getQualities().isEmpty()) {
                // No source either: manifest order is arbitrary, take whatever exists.
                selected = playlist.getQualities().get(0);
            }
            return selected == null ? null : selected.uri();
        } catch (TwitchClient.PlaylistException e) {
            WebStreamerClient.LOGGER.warn("Failed to resolve twitch channel {}: {}", this.channel, e.getMessage());
            return null;
        }
    }

    /**
     * Non-blocking URI access for the renderer: kicks the async resolution when dirty
     * and returns the cached URI once available (null until then).
     */
    public URI getUri(ExecutorService executor) {

        if (this.sourceDirty) {
            this.uri = null;
            this.futureUri = executor.submit(this::resolveUri);
            this.sourceDirty = false;
        }

        if (this.futureUri != null && this.futureUri.isDone()) {
            try {
                this.uri = this.futureUri.get();
            } catch (InterruptedException | CancellationException e) {
                // Cancel should not happen.
            } catch (ExecutionException e) {
                WebStreamerClient.LOGGER.warn("Error resolving display URI.", e);
            } finally {
                this.futureUri = null;
            }
        }

        return this.uri;

    }

    /** Forces a fresh resolution (e.g. a Twitch playlist went stale and the layer was lost). */
    public void resetUri() {
        if (this.channel != null) {
            WebStreamerClient.TWITCH.forgetPlaylist(this.channel);
        }
        this.streamTitle = null;
        this.sourceDirty = true;
    }

    /** Twitch stream title, or null while unresolved / when the channel is idle or offline. */
    public String getStreamTitle() {
        return this.streamTitle;
    }

    /**
     * Re-fetches the title while the stream plays, at most once per
     * {@link #TITLE_REFRESH_INTERVAL_MS}. Called from the render loop; the actual
     * request runs on the resources executor. A failed refresh keeps the last title.
     */
    public void tickTitleRefresh(ExecutorService executor) {
        if (System.currentTimeMillis() - this.titleFetchedAt < TITLE_REFRESH_INTERVAL_MS) {
            return;
        }
        if (!this.titleRefreshing.compareAndSet(false, true)) {
            return;
        }
        executor.submit(() -> {
            try {
                String title = WebStreamerClient.TWITCH.requestStreamTitle(this.channel);
                if (title != null) {
                    this.streamTitle = title;
                }
            } finally {
                this.titleFetchedAt = System.currentTimeMillis();
                this.titleRefreshing.set(false);
            }
        });
    }

}
