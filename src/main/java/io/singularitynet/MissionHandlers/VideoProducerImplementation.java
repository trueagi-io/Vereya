package io.singularitynet.MissionHandlers;

import com.mojang.blaze3d.systems.RenderSystem;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.VideoProducer;
import io.singularitynet.utils.ImageClass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

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
    public ByteBuffer getFrame(MissionInit missionInit)
    {
        if (!this.videoParams.isWantDepth())
        {
            return getRGBFrame(); // Just return the simple RGB, 3bpp image.
        }
        else
            throw new RuntimeException("Depth map not implemented");
    }

    @Override
    public int getWidth()
    {
        return this.videoParams.getWidth();
    }

    @Override
    public int getHeight() { return this.videoParams.getHeight(); }

    private ByteBuffer getRGBFrame()
    {
        Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
        int i = framebuffer.textureWidth;
        int j = framebuffer.textureHeight;
        ImageClass imageclass = new ImageClass(i, j, false);
        RenderSystem.bindTexture(framebuffer.getColorAttachment());
        imageclass.loadFromTextureImage(0, true);
        imageclass.mirrorVertically();
        byte[] ni_bytes;
        try {
             ni_bytes = imageclass.getBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        imageclass.close();
        if (ni_bytes.length <= 0)
            return null;
        else {
            return ByteBuffer.wrap(ni_bytes);
        }
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