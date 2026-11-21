// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A release host on loopback: HTTPS under a self-signed certificate keytool makes for
 * 127.0.0.1, serving {@code root} under {@code /files/} and answering 302 with the Location
 * {@link #redirect} gave a path. Beside it a plain HTTP server that only counts requests and
 * answers 404.
 */
final class ReleaseHost implements AutoCloseable {
    final HttpsServer https;
    final HttpServer http;
    final String thumbprint;
    final AtomicInteger plainRequests = new AtomicInteger();
    final Map<String, String> redirects = new ConcurrentHashMap<>();

    private ReleaseHost(HttpsServer https, HttpServer http, String thumbprint) {
        this.https = https;
        this.http = http;
        this.thumbprint = thumbprint;
    }

    static ReleaseHost start(Path root, Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("tls")).resolve("host.p12");
        char[] password = "fixture".toCharArray();
        Path keytool = Path.of(System.getProperty("java.home"), "bin", Os.isWindows() ? "keytool.exe" : "keytool");
        Processes.Captured gen = Processes.capture(
                List.of(
                        keytool.toString(),
                        "-genkeypair",
                        "-alias",
                        "host",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-dname",
                        "CN=127.0.0.1",
                        "-ext",
                        "SAN=ip:127.0.0.1",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        "fixture",
                        "-keypass",
                        "fixture",
                        "-noprompt"),
                Duration.ofSeconds(60));
        assertThat(gen.ok()).as(gen.output()).isTrue();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, password);
        }
        String thumbprint = HexFormat.of()
                .withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-1")
                        .digest(keys.getCertificate("host").getEncoded()));
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);

        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls));
        https.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ReleaseHost host = new ReleaseHost(https, http, thumbprint);
        https.createContext("/", exchange -> host.answer(exchange, root));
        http.createContext("/", exchange -> {
            host.plainRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        https.start();
        http.start();
        return host;
    }

    private void answer(HttpExchange exchange, Path root) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String location = redirects.get(path);
        Path file = path.startsWith("/files/")
                ? root.resolve(path.substring("/files/".length())).normalize()
                : null;
        if (location != null) {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
        } else if (file != null && file.startsWith(root) && Files.isRegularFile(file)) {
            byte[] body = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
        exchange.close();
    }

    /** {@code https://127.0.0.1:<port>} followed by {@code path}. */
    String url(String path) {
        return "https://127.0.0.1:" + https.getAddress().getPort() + path;
    }

    /** The plain server's {@code http://127.0.0.1:<port>} followed by {@code path}. */
    String plainUrl(String path) {
        return "http://127.0.0.1:" + http.getAddress().getPort() + path;
    }

    /** Answer {@code path} with a 302 to {@code location}, sent as written. */
    void redirect(String path, String location) {
        redirects.put(path, location);
    }

    @Override
    public void close() {
        https.stop(0);
        http.stop(0);
    }
}
