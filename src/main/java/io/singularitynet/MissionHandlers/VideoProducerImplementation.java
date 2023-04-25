package io.singularitynet.MissionHandlers;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sun.jna.platform.unix.X11;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.VideoProducer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.Window;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.awt.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;


import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;

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
        throw new RuntimeException("Depth map not implemented");
    }

//    @Override
//    public int getWidth()
//    {
//        return MinecraftClient.getInstance().getFramebuffer().textureWidth;
//    }
//
//    @Override
//    public int getHeight() { return MinecraftClient.getInstance().getFramebuffer().textureHeight; }


    @Override
    public int getWidth()
    {
        return this.videoParams.getWidth();
    }

    @Override
    public int getHeight() { return this.videoParams.getHeight(); }
    public int getFBWidth()
    {
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        int[] win_width = new int[1];
        int[] win_height = new int[1];
        GLFW.glfwGetFramebufferSize(win, win_width, win_height);
        return win_width[0];
    }

    public int getFBHeight()
    {
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        int[] win_width = new int[1];
        int[] win_height = new int[1];
        GLFW.glfwGetFramebufferSize(win, win_width, win_height);
        return win_height[0];
    }

    public int getRequiredBufferSize()
    {
//        int height = MinecraftClient.getInstance().getFramebuffer().textureHeight;
//        int width = MinecraftClient.getInstance().getFramebuffer().textureWidth;
//        return height * width * (this.videoParams.isWantDepth() ? 4 : 3);
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        int[] win_width = new int[1];
        int[] win_height = new int[1];
        GLFW.glfwGetFramebufferSize(win, win_width, win_height);
        return win_width[0] * win_height[0] * (this.videoParams.isWantDepth() ? 4 : 3);
//        return this.videoParams.getWidth() * this.videoParams.getHeight() * (this.videoParams.isWantDepth() ? 4 : 3);
//        return MinecraftClient.getInstance().getWindow().getFramebufferWidth() *
//                MinecraftClient.getInstance().getWindow().getFramebufferHeight() *
//                (this.videoParams.isWantDepth() ? 4 : 3);
    }

    public void getFBHeightWidth(int[] height, int[] width)
    {
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        GLFW.glfwGetFramebufferSize(win, width, height);
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

    private ByteBuffer getRGBFrame()
    {
        final int format = GL_RGB;
//        final int width = this.videoParams.getWidth();
//        final int height = this.videoParams.getHeight();
//        final int width = MinecraftClient.getInstance().getWindow().getFramebufferWidth();
//        final int height = MinecraftClient.getInstance().getWindow().getFramebufferHeight();
//        final int width = getFBWidth();
//        final int height = getFBHeight();
//        final int chwidth = MinecraftClient.getInstance().getFramebuffer().textureWidth;
//        final int chheight = MinecraftClient.getInstance().getFramebuffer().textureHeight;
        // Now read the pixels out from that:
        // glReadPixels appears to be faster than doing:
        // GlStateManager.bindTexture(this.fbo.framebufferTexture);
        // GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, GL_UNSIGNED_BYTE,
        // buffer);
//        BufferUtils.createByteBuffer();
        Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
        int i = framebuffer.textureWidth;
        int j = framebuffer.textureHeight;
        NativeImage nativeImage = new NativeImage(i, j, false);
        RenderSystem.bindTexture(framebuffer.getColorAttachment());
        nativeImage.loadFromTextureImage(0, true);
        nativeImage.mirrorVertically();
        byte[] ni_bytes;
        try {
             ni_bytes = nativeImage.getBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
//        String tmpdir = System.getProperty("java.io.tmpdir");
//        Path path = Paths.get(tmpdir+"/mc_tmp_image.png");
//        try {
//            nativeImage.writeTo(path);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
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