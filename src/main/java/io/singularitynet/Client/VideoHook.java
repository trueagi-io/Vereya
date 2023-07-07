package io.singularitynet.Client;



import io.singularitynet.MissionHandlerInterfaces.IVideoProducer;
import io.singularitynet.projectmalmo.ClientAgentConnection;
import io.singularitynet.projectmalmo.MissionDiagnostics;
import io.singularitynet.projectmalmo.MissionInit;
import io.singularitynet.utils.AddressHelper;
import io.singularitynet.utils.TCPSocketChannel;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.BufferUtils;
import org.json.*;

import static org.lwjgl.opengl.GL11.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;


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


    private Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();
    /**
     * Calling stop() if we're not running is a no-op.
     */
    private boolean isRunning = false;

    private String tictac = "tic";

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
    private int renderedWidth = 0;
    private int renderedHeight = 0;

    private int texChannels = 4;

    ByteBuffer buffer;
    // check also Malmo/src/TimestampedVideoFrame.h

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
        this.buffer = BufferUtils.createByteBuffer(this.framebuffer.textureWidth * this.framebuffer.textureHeight * this.texChannels);
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

        if (this.renderWidth == 0 || this.renderHeight == 0) {
            return;
        }
        // resize the window if we need to
        if( this.renderedWidth == 0 ) {
            this.renderedWidth = this.renderWidth;
            this.renderedHeight = this.renderHeight;
            LogManager.getLogger().debug("resizing to " + this.renderHeight + " " + this.renderedWidth);
        }
        int oldRenderWidth = window.getFramebufferWidth();
        int oldRenderHeight = window.getFramebufferHeight();
        if( this.renderedWidth == oldRenderWidth && this.renderedHeight == oldRenderHeight )
            return;

        // Store width and height obtained in the same way as to be compared to
        window.setWindowedSize(this.renderWidth, this.renderHeight);
        this.renderedWidth = window.getFramebufferWidth();
        this.renderedHeight = window.getFramebufferHeight();
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
        // but currently we allow resizing
        // resizeIfNeeded();
    }

    private static int pack(int x, int y) {
        return y * 4 + x;
    }


    public void readColumnMajor(float[] float_arr, FloatBuffer buf) {
        float_arr[0] = buf.get(pack(0, 0));
        float_arr[1] = buf.get(pack(0, 1));
        float_arr[2] = buf.get(pack(0, 2));
        float_arr[3] = buf.get(pack(0, 3));
        float_arr[4] = buf.get(pack(1, 0));
        float_arr[5] = buf.get(pack(1, 1));
        float_arr[6] = buf.get(pack(1, 2));
        float_arr[7] = buf.get(pack(1, 3));
        float_arr[8] = buf.get(pack(2, 0));
        float_arr[9] = buf.get(pack(2, 1));
        float_arr[10] = buf.get(pack(2, 2));
        float_arr[11] = buf.get(pack(2, 3));
        float_arr[12] = buf.get(pack(3, 0));
        float_arr[13] = buf.get(pack(3, 1));
        float_arr[14] = buf.get(pack(3, 2));
        float_arr[15] = buf.get(pack(3, 3));
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

        long time_after_render_ns = 0;

        try
        {
            if (AddressHelper.getMissionControlPort() == 0 || tictac.equals("tac")) {
                tictac = "tic";
                success = true;
                time_after_render_ns = System.nanoTime();
            } else {
                tictac = "tac";
                Map<String, Number> header_map = new HashMap<>();
                this.buffer.clear();
                if (this.framebuffer.textureWidth * this.framebuffer.textureHeight * this.texChannels != this.buffer.limit())
                {
                    this.buffer = BufferUtils.createByteBuffer(this.framebuffer.textureWidth * this.framebuffer.textureHeight * this.texChannels);
                }
                header_map.put("x", x);
                header_map.put("y", y);
                header_map.put("z", z);
                header_map.put("yaw", yaw);
                header_map.put("pitch", pitch);
                glGetFloatv(GL_PROJECTION_MATRIX, projection);
                glGetFloatv(GL_MODELVIEW_MATRIX, modelview);
                int[] sizes = this.videoProducer.writeFrame(this.missionInit, this.buffer);
                header_map.put("img_width", sizes[0]);
                header_map.put("img_height", sizes[1]);
                header_map.put("img_ch", this.texChannels);
                JSONObject jo_header = new JSONObject(header_map);
                float[] proj_floats = new float[16];
                float[] modelview_floats = new float[16];
                readColumnMajor(proj_floats, projection.asReadOnlyBuffer());
                readColumnMajor(modelview_floats, modelview.asReadOnlyBuffer());
                jo_header.append("projectionMatrix", proj_floats);
                jo_header.append("modelViewMatrix", modelview_floats);
                byte[] jo_bytes = jo_header.toString().getBytes(StandardCharsets.UTF_8);
                int jo_len = jo_bytes.length;
                time_after_render_ns = System.nanoTime();
                if (this.buffer != null) {
                    ByteBuffer jo_len_buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(jo_len);
                    jo_len_buffer.flip();
                    int frame_buf_len = this.buffer.capacity();
                    this.buffer.limit(frame_buf_len);
                    ByteBuffer[] buffers = {jo_len_buffer, ByteBuffer.wrap(jo_bytes), this.buffer};
                    success = this.connection.sendTCPBytes(buffers, jo_len + frame_buf_len + 4);
                }
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
