package fr.thegentlesuits.webstreamer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.net.URI;
import java.util.HashMap;

/**
 * Caches one {@link DisplayLayerHls} per unique stream URI so displays showing the same
 * stream share a single decode pipeline. Render thread only.
 *
 * <p>Ported from fabric-webstreamer by Theo Rozier (LGPL-3.0), flattened to HLS only.</p>
 */
@Environment(EnvType.CLIENT)
public class DisplayLayerManager {

    /** Max total cost for concurrent layers (an HLS layer costs 30). */
    private static final int MAX_LAYERS_COST = 20 * 30;

    /** Interval of cleanups for unused display layers. */
    private static final long CLEANUP_INTERVAL = 5L * 1000000000L;

    private final HashMap<URI, DisplayLayerHls> layers = new HashMap<>();

    /** Common pools for shared and reusable heavy buffers. */
    private final DisplayLayerResources res = new DisplayLayerResources();

    /** Time in nanoseconds (monotonic) of the last cleanup for unused layers. */
    private long lastCleanup = 0;

    public DisplayLayerResources getResources() {
        return this.res;
    }

    public static class OutOfLayerException extends Exception {}

    /** Get or create the layer for a stream URI. */
    public DisplayLayerHls getLayer(URI uri) throws OutOfLayerException {
        DisplayLayerHls layer = this.layers.get(uri);
        if (layer == null) {
            if (this.cost() + 30 > MAX_LAYERS_COST) {
                throw new OutOfLayerException();
            }
            layer = new DisplayLayerHls(uri, this.res);
            this.layers.put(uri, layer);
        }
        return layer;
    }

    private int cost() {
        return this.layers.values().stream().mapToInt(DisplayLayerHls::cost).sum();
    }

    /** Per-frame tick: advances every layer, then periodically frees unused ones. */
    public void tick() {
        RenderSystem.assertOnRenderThread();
        this.layers.values().forEach(DisplayLayerHls::tick);

        long now = System.nanoTime();
        if (now - this.lastCleanup >= CLEANUP_INTERVAL) {
            this.layers.values().removeIf(layer -> layer.cleanup(now));
            this.lastCleanup = now;
        }
    }

    /** Force-frees every layer (world quit / disconnect). */
    public void cleanupAll() {
        this.layers.values().removeIf(layer -> layer.cleanup(0));
    }

}
