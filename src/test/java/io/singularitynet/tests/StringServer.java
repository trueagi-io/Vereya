package io.singularitynet.tests;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Server that decodes each packet as UTF-8 string and forwards to a handler. */
public final class StringServer {
    private final int port;
    private final Consumer<String> handleString;
    private final TCPServer tcp;
    private Thread thread;

    public StringServer(int port, Consumer<String> handleString) {
        this.port = port;
        this.handleString = handleString;
        this.tcp = new TCPServer(port, (tv) -> {
            String s = new String(tv.data, StandardCharsets.UTF_8);
            handleString.accept(s);
        });
    }

    public Thread start() { this.thread = tcp.start("StringServer-" + port); return this.thread; }
    public void stop() { tcp.stop(); }
}

