package io.singularitynet.Client;


import com.mojang.blaze3d.platform.GlStateManager;
import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.ClientAgentConnection;
import io.singularitynet.projectmalmo.MissionDiagnostics;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.utils.AddressHelper;
import io.singularitynet.utils.TCPSocketChannel;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.ClosedChannelException;


/**
 * Register this class on the MinecraftForge.EVENT_BUS to intercept video
 * frames.
 * <p>
 * We use this to send video frames over sockets.
 */
public class VideoHook {

    private static FloatBuffer createDirectFloatBuffer(int number){
        ByteBuffer vbb = ByteBuffer.allocateDirect(number * 4);
        vbb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = vbb.asFloatBuffer();
        return fb;
    }

    public static final FloatBuffer projection = createDirectFloatBuffer(16);
    public static final FloatBuffer modelview = createDirectFloatBuffer(16);
    /**
     * If the sockets are not yet open we delay before retrying. Value is in
     * nanoseconds.
     */
    private static final long RETRY_GAP_NS = 5000000000L;

    /**
     * The time in nanoseconds after which we should try sending again.
     */
    private long retry_time_ns = 0;

    /**
     * Calling stop() if we're not running is a no-op.
     */
    private boolean isRunning = false;

    /**
     * MissionInit object for passing to the IVideoProducer.
     */
    private MissionInit missionInit;

    /**
     * Object that will provide the actual video frame on demand.
     */
    private IVideoProducer videoProducer;

    /**
     * Public count of consecutive TCP failures - used to terminate a mission if nothing is listening
     */
    public int failedTCPSendCount = 0;

    /**
     * Object which maintains our connection to the agent.
     */
    private TCPSocketChannel connection = null;

    private int renderWidth;

    private int renderHeight;

    ByteBuffer buffer = null;
    ByteBuffer headerbuffer = null;
    // check also Malmo/src/TimestampedVideoFrame.h
    final int POS_HEADER_SIZE = 20 + (16 * 4 * 2); // 20 bytes for the five floats governing x,y,z,yaw and pitch.
    // + 16 bytes for projection matrix

    // For diagnostic purposes:
    private long timeOfFirstFrame = 0;
    private long timeOfLastFrame = 0;
    private long framesSent = 0;
    private VideoProducedObserver observer;

    /**
     * Resize the rendering and start sending video over TCP.
     */
    public void start(MissionInit missionInit, IVideoProducer videoProducer, VideoProducedObserver observer)
    {
        if (videoProducer == null)
        {
            return; // Don't start up if there is nothing to provide the video.
        }

        videoProducer.prepare(missionInit);
        this.missionInit = missionInit;
        this.videoProducer = videoProducer;
        this.observer = observer;
        this.buffer = BufferUtils.createByteBuffer(this.videoProducer.getRequiredBufferSize());
        this.headerbuffer = ByteBuffer.allocate(20 + (16 * 4 * 2)).order(ByteOrder.BIG_ENDIAN);
        this.renderWidth = videoProducer.getWidth();
        this.renderHeight = videoProducer.getHeight();
        resizeIfNeeded();
        // Display.setResizable(false); // prevent the user from resizing using the window borders

        ClientAgentConnection cac = missionInit.getClientAgentConnection();
        if (cac == null)
            return;	// Don't start up if we don't have any connection details.

        String agentIPAddress = cac.getAgentIPAddress();
        int agentPort = 0;
        switch (videoProducer.getVideoType())
        {
            case LUMINANCE:
                agentPort = cac.getAgentLuminancePort();
                break;
            case DEPTH_MAP:
                agentPort = cac.getAgentDepthPort();
                break;
            case VIDEO:
                agentPort = cac.getAgentVideoPort();
                break;
            case COLOUR_MAP:
                agentPort = cac.getAgentColourMapPort();
                break;
        }

        this.connection = new TCPSocketChannel(agentIPAddress, agentPort, "vid");
        this.failedTCPSendCount = 0;

        try
        {
            WorldRenderEvents.START.register((context -> { this.onRenderStart(context);}));
            WorldRenderEvents.END.register((context -> { this.postRender(context);}));
            // MinecraftForge.EVENT_BUS.register(this);
        }
        catch(Exception e)
        {
            System.out.println("Failed to register video hook: " + e);
            throw e;
        }
        this.isRunning = true;
    }

    /**
     * Resizes the window and the Minecraft rendering if necessary. Set renderWidth and renderHeight first.
     */
    private void resizeIfNeeded()
    {
        MinecraftClient instance = MinecraftClient.getInstance();
        Window window = instance.getWindow();
        if (window == null){
            return;
        }
        // resize the window if we need to
        int oldRenderWidth = window.getFramebufferWidth();
        int oldRenderHeight = window.getFramebufferHeight();
        if( this.renderWidth == oldRenderWidth && this.renderHeight == oldRenderHeight )
            return;

        window.setWindowedSize(this.renderWidth, this.renderHeight);
        // these will cause a number of visual artefacts
//        window.setFramebufferHeight(this.renderHeight);
//        window.setFramebufferWidth(this.renderWidth);
    }

    /**
     * Stop sending video.
     */
    public void stop(MissionDiagnostics diags)
    {
        if( !this.isRunning )
        {
            return;
        }
        if (this.videoProducer != null)
            this.videoProducer.cleanup();

        // stop sending video frames
        /*
        try
        {
            MinecraftForge.EVENT_BUS.unregister(this);
        }
        catch(Exception e)
        {
            System.out.println("Failed to unregister video hook: " + e);
        } */
        // Close our TCP socket:
        this.connection.close();
        this.isRunning = false;

        // allow the user to resize the window again
        // Display.setResizable(true);

        // And fill in some diagnostic data:
        if (diags != null)
        {
            MissionDiagnostics.VideoData vd = new MissionDiagnostics.VideoData();
            vd.setFrameType(this.videoProducer.getVideoType().toString());
            vd.setFramesSent((int) this.framesSent);
            if (this.timeOfLastFrame == this.timeOfFirstFrame)
                vd.setAverageFpsSent(new BigDecimal(0));
            else
                vd.setAverageFpsSent(new BigDecimal(1000.0 * this.framesSent / (this.timeOfLastFrame - this.timeOfFirstFrame)));
            diags.getVideoData().add(vd);
        }
    }

    /**
     * Called before and after the rendering of the world.
     *
     * @param event
     *            Contains information about the event.
     */

    public void onRenderStart(WorldRenderContext event) {
        // this is here in case the user has resized the window during a mission
        resizeIfNeeded();
    }

    protected void writeProjectionMatrix(ByteBuffer buffer, Matrix4f result){
        FloatBuffer buf = buffer.asFloatBuffer();
        result.writeRowMajor(buf);
        buffer.position(buffer.position() + 16 * 4);
    }

    /**
     * Called when the world has been rendered but not yet the GUI or player hand.
     *
     * @param event
     *            Contains information about the event (not used).
     */
    public void postRender(WorldRenderContext event)
    {
        // Check that the video producer and frame type match - eg if this is a colourmap frame, then
        // only the colourmap videoproducer needs to do anything.
        /*
        boolean colourmapFrame = TextureHelper.colourmapFrame;
        boolean colourmapVideoProducer = this.videoProducer.getVideoType() == IVideoProducer.VideoType.COLOUR_MAP;
        if (colourmapFrame != colourmapVideoProducer)
            return;*/

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        Vec3d pos = player.getPos();
        float x = (float) pos.getX();
        float y = (float) pos.getX();
        float z = (float) pos.getX();
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        /*
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * event.getPartialTicks();
        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * event.getPartialTicks();*/

        long time_now = System.nanoTime();

        if (observer != null)
            observer.frameProduced();

        if (time_now < retry_time_ns)
            return;

        boolean success = false;

        long time_after_render_ns;

        try
        {
            int size = this.videoProducer.getRequiredBufferSize();

            if (AddressHelper.getMissionControlPort() == 0) {
                success = true;
                time_after_render_ns = System.nanoTime();
            } else {
                // Get buffer ready for writing to:
                this.buffer.clear();
                this.headerbuffer.clear();
                // Write the pos data:
                this.headerbuffer.putFloat(x);
                this.headerbuffer.putFloat(y);
                this.headerbuffer.putFloat(z);
                this.headerbuffer.putFloat(yaw);
                this.headerbuffer.putFloat(pitch);
                glGetFloatv(GL_PROJECTION_MATRIX, projection);
                glGetFloatv(GL_MODELVIEW_MATRIX, modelview);
                Matrix4f projectionMatrix = new Matrix4f();
                projectionMatrix.readColumnMajor(projection.asReadOnlyBuffer());
                Matrix4f modelViewMatrix = new Matrix4f();
                modelViewMatrix.readColumnMajor(modelview.asReadOnlyBuffer());

                this.writeProjectionMatrix(this.headerbuffer, modelViewMatrix);
                this.writeProjectionMatrix(this.headerbuffer, projectionMatrix);
                assert(this.headerbuffer.remaining() == 0);
                // Write the frame data:
                this.videoProducer.getFrame(this.missionInit, this.buffer);
                // The buffer gets flipped by getFrame(), but we need to flip our header buffer ourselves:
                this.headerbuffer.flip();
                ByteBuffer[] buffers = {this.headerbuffer, this.buffer};
                time_after_render_ns = System.nanoTime();

                success = this.connection.sendTCPBytes(buffers, size + POS_HEADER_SIZE);
            }

            long time_after_ns = System.nanoTime();
            float ms_send = (time_after_ns - time_after_render_ns) / 1000000.0f;
            float ms_render = (time_after_render_ns - time_now) / 1000000.0f;
            if (success)
            {
                this.failedTCPSendCount = 0;    // Reset count of failed sends.
                this.timeOfLastFrame = System.currentTimeMillis();
                if (this.timeOfFirstFrame == 0)
                    this.timeOfFirstFrame = this.timeOfLastFrame;
                this.framesSent++;
                //            System.out.format("Total: %.2fms; collecting took %.2fms; sending %d bytes took %.2fms\n", ms_send + ms_render, ms_render, size, ms_send);
                //            System.out.println("Collect: " + ms_render + "; Send: " + ms_send);
            }
        }
        catch (Exception e)
        {
            System.out.format(e.getMessage());
        }

        if (!success) {
            System.out.format("Failed to send frame - will retry in %d seconds\n", RETRY_GAP_NS / 1000000000L);
            if (this.connection.exception != null){
                System.out.println("reconnecting");
                this.connection = new TCPSocketChannel(connection.getAddress(), connection.getPort(), "vid");
            }

            retry_time_ns = time_now + RETRY_GAP_NS;
            this.failedTCPSendCount++;
        }
    }
}
