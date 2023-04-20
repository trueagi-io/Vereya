package io.singularitynet.MissionHandlers;

import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.VideoProducer;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;


import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import static org.lwjgl.opengl.GL11.*;

public class VideoProducerImplementation extends HandlerBase implements IVideoProducer
{
    private VideoProducer videoParams;
    private FloatBuffer depthBuffer;

    @Override
    public boolean parseParameters(Object params)
    {
        if (params == null || !(params instanceof VideoProducer))
            return false;
        this.videoParams = (VideoProducer) params;

        return true;
    }

    @Override
    public VideoType getVideoType()
    {
        return VideoType.VIDEO;
    }

    @Override
    public void getFrame(MissionInit missionInit, ByteBuffer buffer)
    {
        if (!this.videoParams.isWantDepth())
        {
            getRGBFrame(buffer); // Just return the simple RGB, 3bpp image.
            return;
        }
        throw new RuntimeException("Depth map not implemented");
    }

    @Override
    public int getWidth()
    {
        return this.videoParams.getWidth();
    }

    @Override
    public int getHeight()
    {
        return this.videoParams.getHeight();
    }

    public int getRequiredBufferSize()
    {
        return this.videoParams.getWidth() * this.videoParams.getHeight() * (this.videoParams.isWantDepth() ? 4 : 3);
//        return MinecraftClient.getInstance().getWindow().getFramebufferWidth() *
//                MinecraftClient.getInstance().getWindow().getFramebufferHeight() *
//                (this.videoParams.isWantDepth() ? 4 : 3);
    }

    private static void byteBuffer2BufferedImage(ByteBuffer bb,
                                                BufferedImage bi) {
        final int bytesPerPixel = 3;
        byte[] imageArray = ((DataBufferByte) bi.getRaster()
                .getDataBuffer()).getData();
        bb.rewind();
        bb.get(imageArray);
        int numPixels = bb.capacity() / bytesPerPixel;
        for (int i = 0; i < numPixels; i++) {
            byte tmp = imageArray[i * bytesPerPixel];
            imageArray[i * bytesPerPixel] = imageArray[i * bytesPerPixel
                    + 2];
            imageArray[i * bytesPerPixel + 2] = tmp;
        }
    }

    private void getRGBFrame(ByteBuffer buffer)
    {
        final int format = GL_RGB;
        final int width = this.videoParams.getWidth();
        final int height = this.videoParams.getHeight();
//        final int width = MinecraftClient.getInstance().getWindow().getFramebufferWidth();
//        final int height = MinecraftClient.getInstance().getWindow().getFramebufferHeight();

        // Now read the pixels out from that:
        // glReadPixels appears to be faster than doing:
        // GlStateManager.bindTexture(this.fbo.framebufferTexture);
        // GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, GL_UNSIGNED_BYTE,
        // buffer);
        GlStateManager._readPixels(0, 0, width, height, format, GL_UNSIGNED_BYTE, buffer);
//        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
//        byteBuffer2BufferedImage(buffer, bi);
//        int stub = 0;
        // Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
    }

    @Override
    public void prepare(MissionInit missionInit)
    {
        boolean useDepth = this.videoParams.isWantDepth();
        // Create a buffer for retrieving the depth map, if requested:
        if (this.videoParams.isWantDepth())
            this.depthBuffer = BufferUtils.createFloatBuffer(this.videoParams.getWidth() * this.videoParams.getHeight());
        // Set the requested camera position
        // Minecraft.getMinecraft().gameSettings.thirdPersonView = this.videoParams.getViewpoint();
    }

    @Override
    public void cleanup() {}
}