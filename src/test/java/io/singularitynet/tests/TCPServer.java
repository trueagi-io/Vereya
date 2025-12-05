package io.singularitynet.tests;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Generic TCP server that reads length-prefixed packets and forwards them to a callback. */
public final class TCPServer implements Runnable {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(TCPServer.class.getName());
    private final int port;
    private final Consumer<TimestampedByteVector> callback;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private Thread thread;

    public TCPServer(int port, Consumer<TimestampedByteVector> callback) {
        this.port = port;
        this.callback = callback;
    }

    public Thread start(String name) {
        if (thread != null) return thread;
        thread = new Thread(this, name != null ? name : ("TCPServer-" + port));
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public void stop() {
        stop.set(true);
        try { if (thread != null) thread.interrupt(); } catch (Throwable ignored) {}
    }

    @Override public void run() {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            while (!stop.get()) {
                try (Socket s = ss.accept()) {
                    s.setTcpNoDelay(true);
                    InputStream in = s.getInputStream();
                    while (!stop.get()) {
                        int len = TestUtils.readIntBE(in);
                        if (len <= 0 || len > 50_000_000) break;
                        byte[] payload = TestUtils.readFully(in, len);
                        long ts = System.nanoTime();
                        try {
                            callback.accept(new TimestampedByteVector(ts, payload));
                        } catch (Throwable t) {
                            LOG.warning("TCPServer callback error: " + t.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    // continue accepting
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

