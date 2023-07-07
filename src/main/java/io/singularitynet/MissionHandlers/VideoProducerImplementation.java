package io.singularitynet.MissionHandlers;

import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.VideoProducer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.AbstractMap;

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
    public AbstractMap.Entry<ByteBuffer, int[]> getFrame(MissionInit missionInit)
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

    private AbstractMap.Entry<ByteBuffer, int[]> getRGBFrame()
    {
        Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
        int i = framebuffer.textureWidth;
        int j = framebuffer.textureHeight;
        ByteBuffer resBuffer = BufferUtils.createByteBuffer(i * j * 4);
        resBuffer.flip();
        GlStateManager._readPixels(0, 0, i, j, NativeImage.Format.RGBA.toGl(), GL11.GL_UNSIGNED_BYTE, resBuffer);
        int[] sizes = new int[3];
        sizes[0] = i;
        sizes[1] = j;
        sizes[2] = 4; //since RGBA image
        AbstractMap.Entry<ByteBuffer, int[]> res = new AbstractMap.SimpleEntry<>(resBuffer , sizes);
        return res;
    }

    @Override
    public void prepare(MissionInit missionInit)
    {
        boolean useDepth = this.videoParams.isWantDepth();
        // Create a buffer for retrieving the depth map, if requested:
        if (useDepth)
            this.depthBuffer = BufferUtils.createFloatBuffer(this.videoParams.getWidth() * this.videoParams.getHeight());
        // Set the requested camera position
        // Minecraft.getMinecraft().gameSettings.thirdPersonView = this.videoParams.getViewpoint();
    }

    @Override
    public void cleanup() {}
}