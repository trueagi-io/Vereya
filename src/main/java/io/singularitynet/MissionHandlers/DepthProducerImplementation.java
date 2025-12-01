package io.singularitynet.MissionHandlers;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.DepthProducer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Dedicated depth-map producer.
 *
 * - VideoType: DEPTH_MAP
 * - Output: 2 bytes per pixel (little-endian uint16), where the stored value
 *   is a linearized depth in view space, scaled into [0, 65535].
 * - The client can reinterpret these two bytes as a float16 or other format.
 *
 * Depth is read once per frame from the normal world render's depth buffer;
 * no additional geometry render pass is performed.
 */
public class DepthProducerImplementation extends HandlerBase implements IVideoProducer {

    private DepthProducer params;
    private SimpleFramebuffer fbo;
    private FloatBuffer depthBuffer;

    @Override
    public boolean parseParameters(Object xmlParams) {
        if (xmlParams == null || !(xmlParams instanceof DepthProducer)) {
            return false;
        }
        this.params = (DepthProducer) xmlParams;
        return true;
    }

    @Override
    public VideoType getVideoType() {
        return VideoType.DEPTH_MAP;
    }

    @Override
    public int[] writeFrame(MissionInit missionInit, ByteBuffer buffer) {
        int width = Math.max(1, getWidth());
        int height = Math.max(1, getHeight());
        int pixelCount = width * height;
        int requiredBytes = pixelCount * 2; // 2 bytes per pixel (uint16)

        if (buffer == null || buffer.capacity() < requiredBytes) {
            throw new IllegalArgumentException("DepthProducer buffer capacity too small: required=" + requiredBytes);
        }

        // Ensure we have enough space for depth floats.
        if (this.depthBuffer == null || this.depthBuffer.capacity() < pixelCount) {
            this.depthBuffer = BufferUtils.createFloatBuffer(pixelCount);
        } else {
            this.depthBuffer.clear();
        }

        // Read depth from a dedicated FBO which has just been populated by
        // blitting colour+depth from the main framebuffer. This mirrors the
        // original Malmo implementation and ensures correctness even when the
        // main framebuffer is multisampled or has a different size.
        Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
        int fbWidth = main.textureWidth;
        int fbHeight = main.textureHeight;

        int readWidth = Math.min(width, fbWidth);
        int readHeight = Math.min(height, fbHeight);

        // Blit depth (and colour, though we only use depth) into our FBO.
        GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, main.fbo);
        GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, this.fbo.fbo);
        GlStateManager._glBlitFrameBuffer(
                0, 0, fbWidth, fbHeight,
                0, 0, width, height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT,
                GL11.GL_NEAREST
        );

        // Bind our FBO for reading depth.
        this.fbo.beginWrite(true);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, this.depthBuffer);
        this.fbo.endWrite();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, 0);

        // Linearize depth and pack into uint16.
        float zNear = 0.05f;
        // Use the current far-plane distance from the game renderer.
        float viewDistance = MinecraftClient.getInstance().gameRenderer.getViewDistance();
        float zFar = viewDistance * 4.0f;

        float minLinear = Float.POSITIVE_INFINITY;
        float maxLinear = 0.0f;

        // First pass: convert to linear depth and find range.
        for (int i = 0; i < readWidth * readHeight; i++) {
            float z = this.depthBuffer.get(i);
            // Convert depth buffer value z (0..1) back to clip space [-1,1].
            float zClip = z * 2.0f - 1.0f;
            // Reconstruct view-space depth in front of the camera. This uses
            // the same convention as the original Malmo implementation:
            // positive distances increasing from zNear to zFar.
            float zLinear = (2.0f * zNear * zFar) / (zFar + zNear - zClip * (zFar - zNear));
            this.depthBuffer.put(i, zLinear);
            if (zLinear < minLinear) minLinear = zLinear;
            if (zLinear > maxLinear) maxLinear = zLinear;
        }

        if (!Float.isFinite(minLinear)) {
            minLinear = 0.0f;
        }
        if (!Float.isFinite(maxLinear) || maxLinear <= minLinear) {
            maxLinear = minLinear + 1.0f;
        }

        float range = maxLinear - minLinear;
        if (range < 1e-6f) range = 1e-6f;
        float scale = 65535.0f / range;

        // Second pass: scale into [0, 65535] and pack as uint16 (little-endian).
        buffer.clear();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                float linear;
                if (x < readWidth && y < readHeight) {
                    linear = this.depthBuffer.get(y * readWidth + x);
                } else {
                    linear = maxLinear;
                }
                float v = (linear - minLinear) * scale;
                int u16 = (int) v;
                if (u16 < 0) u16 = 0;
                else if (u16 > 65535) u16 = 65535;
                // little-endian: low byte, high byte
                buffer.put((byte) (u16 & 0xFF));
                buffer.put((byte) ((u16 >>> 8) & 0xFF));
            }
        }
        buffer.flip();

        return new int[]{width, height};
    }

    @Override
    public int getWidth() {
        return this.params != null ? this.params.getWidth() : 0;
    }

    @Override
    public int getHeight() {
        return this.params != null ? this.params.getHeight() : 0;
    }

    @Override
    public void prepare(MissionInit missionInit) {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        // Create an off-screen FBO to receive a blitted copy of the main
        // framebuffer's colour+depth; this avoids issues with multisample and
        // differing resolutions.
        this.fbo = new SimpleFramebuffer(w, h, true, MinecraftClient.IS_SYSTEM_MAC);
        // Allocate depth buffer lazily on first frame.
        this.depthBuffer = null;
        // Ensure depth testing is enabled for world rendering.
        GlStateManager._enableDepthTest();
    }

    @Override
    public void cleanup() {
        if (this.fbo != null) {
            this.fbo.delete();
            this.fbo = null;
        }
        this.depthBuffer = null;
    }
}
