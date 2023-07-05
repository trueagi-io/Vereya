package io.singularitynet.MissionHandlers;

import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.projectmalmo.VideoProducer;
import io.singularitynet.utils.ImageClass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.AbstractMap;
import java.util.Map;

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
        long time1 = System.nanoTime();
        ImageClass imageclass = new ImageClass(i, j, false);
        long time2 = System.nanoTime();
//        RenderSystem.bindTexture(framebuffer.getColorAttachment());
//        ByteBuffer res = BufferUtils.createByteBuffer(i * j * 4);
//        res.flip();
        ByteBuffer resBuffer = imageclass.getImageAsByteBuffer();
        int[] sizes = new int[3];
        sizes[0] = i;
        sizes[1] = j;
        sizes[2] = 4; //since RGBA image
        AbstractMap.Entry<ByteBuffer, int[]> res = new AbstractMap.SimpleEntry<>(resBuffer , sizes);
//        GlStateManager._readPixels(0, 0, imageclass.getWidth(), imageclass.getHeight(), GL_RGBA, GL11.GL_UNSIGNED_BYTE, res);
        return res;
//        return imageclass.getImageAsByteBuffer(0);
//        long time3 = System.nanoTime();
//        imageclass.loadFromTextureImage(0, false);
//        long time4 = System.nanoTime();
//        imageclass.mirrorVertically();
//        long time5 = System.nanoTime();
//        byte[] ni_bytes;
//        try {
//             ni_bytes = imageclass.getBytes();
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//        long time6 = System.nanoTime();
//        imageclass.close();
//        long time7 = System.nanoTime();
//        if (ni_bytes.length <= 0)
//            return null;
//        else {
//            ByteBuffer res = ByteBuffer.wrap(ni_bytes);
//            long time8 = System.nanoTime();
//            float imageclass_creation = (time2 - time1) / 1000000.0f;
//            float bindTexture = (time3 - time2) / 1000000.0f;
//            float loadFromTextureImage = (time4 - time3) / 1000000.0f;
//            float mirrorVertically = (time5 - time4) / 1000000.0f;
//            float getBytes = (time6 - time5) / 1000000.0f;
//            float imageclass_close = (time7 - time6) / 1000000.0f;
//            float wrap = (time8 - time7) / 1000000.0f;
//            return res;
//        }
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