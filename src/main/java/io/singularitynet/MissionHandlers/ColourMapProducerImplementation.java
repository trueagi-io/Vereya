package io.singularitynet.MissionHandlers;

import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.ColourMapProducer;
import io.singularitynet.projectmalmo.EntityTypes;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.MobWithColour;
import io.singularitynet.utils.TextureHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL12.GL_BGRA;

public class ColourMapProducerImplementation extends HandlerBase implements IVideoProducer {
    private static final Logger LOGGER = LogManager.getLogger(ColourMapProducerImplementation.class);
    private static final int CHANNEL_COUNT = 3;

    private ColourMapProducer cmParams;
    private final Map<String, Integer> mobColours = new HashMap<>();
    private final Map<String, Integer> miscColours = new HashMap<>();
    private Framebuffer segmentationFbo;
    private ByteBuffer readbackBuffer;
    private final int[] frameSize = new int[2];

    @Override
    public boolean parseParameters(Object params) {
        if (!(params instanceof ColourMapProducer)) {
            return false;
        }
        this.cmParams = (ColourMapProducer) params;
        mobColours.clear();
        miscColours.clear();

        for (MobWithColour mob : this.cmParams.getColourSpec()) {
            byte[] colourBytes = mob.getColour();
            if (colourBytes == null || colourBytes.length < 3) {
                continue;
            }
            int colour = ((colourBytes[0] & 0xFF) << 16)
                       | ((colourBytes[1] & 0xFF) << 8)
                       | (colourBytes[2] & 0xFF);
            for (EntityTypes type : mob.getType()) {
                mobColours.put(type.value(), colour);
            }
        }

        miscColours.put("textures/environment/sun.png", 0xFFFF00);
        miscColours.put("textures/environment/moon_phases.png", 0xFFFFFF);
        return true;
    }

    @Override
    public VideoType getVideoType() {
        return VideoType.COLOUR_MAP;
    }

    @Override
    public int getWidth() {
        return this.cmParams != null ? this.cmParams.getWidth() : 0;
    }

    @Override
    public int getHeight() {
        return this.cmParams != null ? this.cmParams.getHeight() : 0;
    }

    @Override
    public int[] writeFrame(MissionInit missionInit, ByteBuffer buffer) {
        Framebuffer fbo = ensureFramebuffer();
        if (fbo != null) {
            this.segmentationFbo = fbo;
        }
        int width = this.segmentationFbo != null ? this.segmentationFbo.textureWidth : Math.max(1, getWidth());
        int height = this.segmentationFbo != null ? this.segmentationFbo.textureHeight : Math.max(1, getHeight());
        int requiredBytes = width * height * CHANNEL_COUNT;

        if (buffer != null && buffer.capacity() < requiredBytes) {
            LOGGER.warn("Provided buffer capacity {} is smaller than required {} for {}x{} colour map", buffer.capacity(), requiredBytes, width, height);
            buffer = null;
        }

        boolean readSuccess = false;
        if (this.segmentationFbo != null) {
            ByteBuffer bgra = ensureReadbackBuffer(width * height * 4);
            bgra.clear();

            int previousFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            try {
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.segmentationFbo.fbo);
                GL30.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GlStateManager._readPixels(0, 0, width, height, GL_BGRA, GL11.GL_UNSIGNED_BYTE, bgra);
                readSuccess = true;
            } catch (Throwable t) {
                LOGGER.warn("Failed to read segmentation framebuffer", t);
            } finally {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFbo);
                GL11.glReadBuffer(previousReadBuffer);
            }

            if (readSuccess && buffer != null) {
                buffer.clear();
                for (int i = 0; i < width * height; i++) {
                    int src = i * 4;
                    byte b = bgra.get(src);
                    byte g = bgra.get(src + 1);
                    byte r = bgra.get(src + 2);
                    buffer.put(b);
                    buffer.put(g);
                    buffer.put(r);
                }
                buffer.flip();
            }
        } else if (buffer != null) {
            buffer.clear();
            buffer.limit(Math.min(buffer.capacity(), requiredBytes));
        }

        frameSize[0] = width;
        frameSize[1] = height;
        return frameSize;
    }

    @Override
    public void prepare(MissionInit missionInit) {
        TextureHelper.setMobColours(mobColours);
        TextureHelper.setMiscTextureColours(miscColours);

        byte[] sky = this.cmParams != null ? this.cmParams.getSkyColour() : null;
        if (sky != null && sky.length >= 3) {
            TextureHelper.setSkyRenderer(new TextureHelper.BlankSkyRenderer(sky));
        } else {
            TextureHelper.setSkyRenderer(null);
        }

        this.segmentationFbo = ensureFramebuffer();
        TextureHelper.setIsProducingColourMap(true);
    }

    @Override
    public void cleanup() {
        TextureHelper.setIsProducingColourMap(false);
        TextureHelper.setSkyRenderer(null);
        if (this.segmentationFbo != null) {
            this.segmentationFbo.delete();
            this.segmentationFbo = null;
        }
    }

    private Framebuffer ensureFramebuffer() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
            if (main != null) {
                width = main.textureWidth;
                height = main.textureHeight;
            }
        }
        width = Math.max(1, width);
        height = Math.max(1, height);
        TextureHelper.ensureSegmentationFramebuffer(width, height);
        return TextureHelper.getSegmentationFramebuffer();
    }

    private ByteBuffer ensureReadbackBuffer(int byteCount) {
        if (this.readbackBuffer == null || this.readbackBuffer.capacity() < byteCount) {
            this.readbackBuffer = BufferUtils.createByteBuffer(byteCount);
        }
        return this.readbackBuffer;
    }
}
