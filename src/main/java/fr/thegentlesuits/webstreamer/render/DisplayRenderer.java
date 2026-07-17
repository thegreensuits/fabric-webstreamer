package fr.thegentlesuits.webstreamer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.thegentlesuits.webstreamer.WebStreamerClient;
import fr.thegentlesuits.webstreamer.sync.ClientDisplay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.AxisAngle4d;
import org.joml.Quaternionf;

import java.net.URI;

/**
 * Draws every synced display as a textured quad during the level render submit phase.
 * Geometry convention ported from upstream fabric-webstreamer's block entity renderer:
 * quad centered on the anchor block, plane offset 0.55 from the local origin toward
 * the configured facing.
 */
@Environment(EnvType.CLIENT)
public class DisplayRenderer {

    /** Displays farther than this from the camera are not rendered at all. */
    private static final double MAX_RENDER_DISTANCE = 128.0;
    /** The stream-title nametag only shows within this distance of the camera. */
    private static final double MAX_TITLE_DISTANCE = 48.0;
    /** Full-bright packed lightmap coordinates (sky 15 / block 15). */
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final Quaternionf ROTATE_90 = new Quaternionf(new AxisAngle4d(Math.PI / 2.0, 0.0, 1.0, 0.0));
    private static final Quaternionf ROTATE_180 = new Quaternionf(new AxisAngle4d(Math.PI, 0.0, 1.0, 0.0));
    private static final Quaternionf ROTATE_270 = new Quaternionf(new AxisAngle4d(Math.PI / 2.0 * 3.0, 0.0, 1.0, 0.0));
    private static final Quaternionf ROTATE_FLOOR = new Quaternionf(new AxisAngle4d(Math.PI / 2.0, 1.0, 0.0, 0.0));
    private static final Quaternionf ROTATE_CEILING = new Quaternionf(new AxisAngle4d(Math.PI / 2.0 * 3.0, 1.0, 0.0, 0.0));

    private static final Identifier IDLE_TEXTURE_ID = Identifier.fromNamespaceAndPath("webstreamer", "display/idle");

    private boolean idleTextureReady;
    private RenderType idleRenderType;

    /** Registered on {@code LevelRenderEvents.COLLECT_SUBMITS}. */
    public void render(LevelRenderContext ctx) {

        // World switch: drop displays synced in a previous level (the server follows up
        // with a fresh FULL). Displays synced in this level are kept — the join-time FULL
        // arrives before the first frame of the new level renders.
        ClientLevel level = Minecraft.getInstance().level;
        WebStreamerClient.DISPLAYS.clearIfStale(level);

        WebStreamerClient.LAYERS.tick();

        if (WebStreamerClient.DISPLAYS.isEmpty()) {
            return;
        }

        CameraRenderState camera = ctx.levelState().cameraRenderState;
        Vec3 cameraPos = camera.pos;
        BlockPos cameraBlockPos = camera.blockPos;

        for (ClientDisplay display : WebStreamerClient.DISPLAYS.all()) {

            BlockPos pos = display.pos;
            double distSq = pos.distToCenterSqr(cameraPos.x, cameraPos.y, cameraPos.z);
            if (distSq > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) {
                continue;
            }

            // Stream aliveness and audio are range-based only — looking away must not
            // pause the sound or unload the decode pipeline. Visibility solely decides
            // whether the quad gets drawn.
            RenderType renderType;
            URI uri = display.hasSource()
                    ? display.getUri(WebStreamerClient.LAYERS.getResources().getExecutor())
                    : null;

            if (uri != null) {
                DisplayLayerHls layer;
                try {
                    layer = WebStreamerClient.LAYERS.getLayer(uri);
                } catch (DisplayLayerManager.OutOfLayerException e) {
                    continue;
                }
                if (layer.isLost()) {
                    // Stale stream (e.g. expired Twitch playlist): forget and re-resolve.
                    display.resetUri();
                    continue;
                }
                layer.markUsed();
                layer.pushAudioSource(pos, pos.distManhattan(cameraBlockPos),
                        display.audioDistance, display.audioVolume);
                renderType = layer.renderType();
                if (distSq < MAX_TITLE_DISTANCE * MAX_TITLE_DISTANCE) {
                    display.tickTitleRefresh(WebStreamerClient.LAYERS.getResources().getExecutor());
                    this.submitTitle(ctx, display, cameraPos, camera, distSq);
                }
            } else {
                // Idle (or still resolving): black placeholder quad.
                renderType = this.idleRenderType();
            }

            float halfExtent = Math.max(display.width, display.height) / 2f + 1f;
            AABB bounds = new AABB(
                    pos.getX() + 0.5 - halfExtent, pos.getY() + 0.5 - halfExtent, pos.getZ() + 0.5 - halfExtent,
                    pos.getX() + 0.5 + halfExtent, pos.getY() + 0.5 + halfExtent, pos.getZ() + 0.5 + halfExtent);
            if (camera.cullFrustum != null && !camera.cullFrustum.isVisible(bounds)) {
                continue;
            }

            this.submitQuad(ctx, display, cameraPos, renderType);

        }

    }

    /**
     * Floating nametag with the Twitch stream title above the screen. The nametag
     * collector billboards, scales and centers the text itself (and adds +0.5 to the
     * attachment's y), so only the anchor translation and the offset are needed here.
     */
    private void submitTitle(LevelRenderContext ctx, ClientDisplay display, Vec3 cameraPos,
                             CameraRenderState camera, double distSq) {

        String title = display.getStreamTitle();
        if (title == null || title.isBlank()) {
            return;
        }

        // Label clearance from the anchor center: above the top edge for wall screens,
        // above a floor screen, below a ceiling screen (above would clip into the ceiling).
        float offsetY = switch (display.attachment) {
            case WALL -> display.height / 2f - 0.15f;
            case FLOOR -> 0.5f;
            case CEILING -> -1.0f;
        };

        PoseStack poseStack = ctx.poseStack();
        poseStack.pushPose();
        BlockPos pos = display.pos;
        poseStack.translate(pos.getX() + 0.5 - cameraPos.x, pos.getY() + 0.5 - cameraPos.y,
                pos.getZ() + 0.5 - cameraPos.z);
        ctx.submitNodeCollector().submitNameTag(poseStack, new Vec3(0.0, offsetY, 0.0), 0,
                Component.literal(title), true, FULL_BRIGHT, distSq, camera);
        poseStack.popPose();

    }

    private void submitQuad(LevelRenderContext ctx, ClientDisplay display, Vec3 cameraPos, RenderType renderType) {

        PoseStack poseStack = ctx.poseStack();
        poseStack.pushPose();

        BlockPos pos = display.pos;
        poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);

        switch (display.attachment) {
            case WALL -> poseStack.translate(0.5f - display.facing.getStepX(), 0.5f, 0.5f - display.facing.getStepZ());
            case FLOOR -> poseStack.translate(0.5f, -0.5f, 0.5f);
            case CEILING -> poseStack.translate(0.5f, 1.5f, 0.5f);
        }

        switch (display.facing) {
            case SOUTH -> poseStack.mulPose(ROTATE_180);
            case EAST -> poseStack.mulPose(ROTATE_270);
            case WEST -> poseStack.mulPose(ROTATE_90);
            default -> { }
        }

        switch (display.attachment) {
            case FLOOR -> poseStack.mulPose(ROTATE_FLOOR);
            case CEILING -> poseStack.mulPose(ROTATE_CEILING);
            default -> { }
        }

        float hw = display.width / 2f;
        float hh = display.height / 2f;

        ctx.submitNodeCollector().submitCustomGeometry(poseStack, renderType, (pose, consumer) -> {
            quadVertex(consumer, pose, hw, -hh, 0, 1);
            quadVertex(consumer, pose, -hw, -hh, 1, 1);
            quadVertex(consumer, pose, -hw, hh, 1, 0);
            quadVertex(consumer, pose, hw, hh, 0, 0);
        });

        poseStack.popPose();

    }

    private static void quadVertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v) {
        consumer.addVertex(pose, x, y, -0.55f)
                .setColor(-1)
                .setUv(u, v)
                .setLight(FULL_BRIGHT);
    }

    private RenderType idleRenderType() {
        if (!this.idleTextureReady) {
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            DisplayTexture idle = new DisplayTexture(IDLE_TEXTURE_ID.toString());
            idle.uploadSolid(24, 24, 30);
            textureManager.register(IDLE_TEXTURE_ID, idle);
            this.idleRenderType = RenderTypes.text(IDLE_TEXTURE_ID);
            this.idleTextureReady = true;
        }
        return this.idleRenderType;
    }

}
