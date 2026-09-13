package demo;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** The JVM half: a JSON API on 8080 (or the port given as the first argument) that Vite proxies `/api` to. */
public final class Api {

    private Api() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = start(port);
        System.out.println(
                "listening on http://localhost:" + server.getAddress().getPort());
    }

    /** Bind every loopback — Node may reach `localhost` over IPv6 — and serve `/api/hello`. */
    static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/hello", exchange -> {
            byte[] body = hello().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    static String hello() {
        return "{\"message\":\"hello from the JVM\",\"at\":\"" + Instant.now() + "\"}";
    }
}
