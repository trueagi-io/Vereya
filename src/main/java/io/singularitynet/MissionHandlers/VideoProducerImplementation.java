package io.singularitynet.MissionHandlers;

import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.VideoProducer;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;


public class VideoProducerImplementation extends HandlerBase implements IVideoProducer
{
    private VideoProducer videoParams;
    private Framebuffer fbo;
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
    }

    private void getRGBFrame(ByteBuffer buffer)
    {
        final int format = GL_RGB;
        final int width = this.videoParams.getWidth();
        final int height = this.videoParams.getHeight();

        // Render the Minecraft frame into our own FBO, at the desired size:
        this.fbo.initFbo(width, height, true);
        // Now read the pixels out from that:
        // glReadPixels appears to be faster than doing:
        // GlStateManager.bindTexture(this.fbo.framebufferTexture);
        // GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, GL_UNSIGNED_BYTE,
        // buffer);
        GlStateManager._readPixels(0, 0, width, height, format, GL_UNSIGNED_BYTE, buffer);
        this.fbo.delete();
        // Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
    }

    @Override
    public void prepare(MissionInit missionInit)
    {
        boolean useDepth = this.videoParams.isWantDepth();
        this.fbo = new SimpleFramebuffer(this.videoParams.getWidth(), this.videoParams.getHeight(),
                useDepth, true);
        // Create a buffer for retrieving the depth map, if requested:
        if (this.videoParams.isWantDepth())
            this.depthBuffer = BufferUtils.createFloatBuffer(this.videoParams.getWidth() * this.videoParams.getHeight());
        // Set the requested camera position
        // Minecraft.getMinecraft().gameSettings.thirdPersonView = this.videoParams.getViewpoint();
    }

    @Override
    public void cleanup()
    {
        this.fbo.delete(); // Must do this or we leak resources.
    }
}