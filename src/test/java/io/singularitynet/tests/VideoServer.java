package io.singularitynet.tests;

import java.util.function.Consumer;

/** Server that parses incoming packets into TimestampedVideoFrame and forwards to handler. */
public final class VideoServer {
    private final int port;
    private final int channels;
    private final FrameType frametype;
    private final Consumer<TimestampedVideoFrame> handleFrame;
    private final TCPServer tcp;
    private Thread thread;

    public VideoServer(int port, int channels, FrameType frametype, Consumer<TimestampedVideoFrame> handleFrame) {
        this.port = port;
        this.channels = channels;
        this.frametype = frametype;
        this.handleFrame = handleFrame;
        this.tcp = new TCPServer(port, (tv) -> {
            TimestampedVideoFrame frame = new TimestampedVideoFrame(tv, frametype);
            handleFrame.accept(frame);
        });
    }

    public Thread start() { this.thread = tcp.start("VideoServer-" + port + "-" + frametype); return this.thread; }
    public void stop() { tcp.stop(); }
}

