package fr.thegentlesuits.webstreamer.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;

/**
 * Dynamic RGBA8 texture receiving decoded video frames. The frame grabber is
 * configured to output RGBA, so frames upload directly through the GPU command
 * encoder without any CPU pixel conversion (padded rows excepted).
 */
@Environment(EnvType.CLIENT)
public class DisplayTexture extends AbstractTexture {

    private final String label;
    private int width = -1;
    private int height = -1;

    private ByteBuffer repackBuffer;

    public DisplayTexture(String label) {
        this.label = label;
    }

    /** Uploads a solid-color 1x1 pixel (ABGR byte order: R,G,B,A in memory). */
    public void uploadSolid(int r, int g, int b) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) 0xFF).flip();
        this.uploadRaw(pixel, 1, 1);
    }

    public void upload(Frame frame) {
        if (frame.imageDepth != Frame.DEPTH_UBYTE || frame.imageChannels != 4) {
            return;
        }

        ByteBuffer data = (ByteBuffer) frame.image[0];
        int frameWidth = frame.imageWidth;
        int frameHeight = frame.imageHeight;
        int stride = frame.imageStride;

        if (stride != frameWidth * 4) {
            // Rows are padded: repack tightly since writeToTexture expects packed rows.
            int tightSize = frameWidth * frameHeight * 4;
            if (this.repackBuffer == null || this.repackBuffer.capacity() < tightSize) {
                this.repackBuffer = ByteBuffer.allocateDirect(tightSize);
            }
            this.repackBuffer.clear();
            for (int row = 0; row < frameHeight; row++) {
                data.limit(row * stride + frameWidth * 4);
                data.position(row * stride);
                this.repackBuffer.put(data);
            }
            this.repackBuffer.flip();
            data = this.repackBuffer;
        }

        this.uploadRaw(data, frameWidth, frameHeight);
    }

    private void uploadRaw(ByteBuffer data, int newWidth, int newHeight) {
        RenderSystem.assertOnRenderThread();

        GpuDevice device = RenderSystem.getDevice();

        if (this.texture == null || this.width != newWidth || this.height != newHeight) {
            // Closes the view along with the texture, so a resize does not leak the old view.
            this.releaseTextures();
            this.texture = device.createTexture(this.label,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, newWidth, newHeight, 1, 1);
            this.textureView = device.createTextureView(this.texture);
            // The renderer's shared sampler cache, as DynamicTexture uses; nothing to close here.
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            this.width = newWidth;
            this.height = newHeight;
        }

        device.createCommandEncoder().writeToTexture(this.texture, data,
                0, 0, 0, 0, newWidth, newHeight);
    }

}
