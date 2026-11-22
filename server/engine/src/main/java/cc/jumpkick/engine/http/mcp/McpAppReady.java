// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * When a started app is ready: its port answers a TCP connect, or its {@code [dev] ready} URL
 * answers 2xx/3xx, or its output matches {@code [dev] ready-pattern}. With no port named up front,
 * the port is the first one the app's own output says it listens on ({@code Tomcat started on port
 * 8080}, {@code Listening on http://0.0.0.0:8080}); it then has to answer too.
 */
final class McpAppReady {

    /** How often the probe looks again. */
    static final long POLL_MILLIS = 250;

    /**
     * A line where a server says where it listens: an {@code http(s)} URL on this machine, or
     * {@code on port 8080} / {@code with port 8080} / {@code port(s): 8080}. Not a {@code
     * jdbc:} or {@code redis:} address the app connects to.
     */
    private static final Pattern LISTENS = Pattern.compile(
            "\\bhttps?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1?]|\\[::])(?::(\\d{2,5}))"
                    + "|\\b(?:on|with) port\\s+(\\d{2,5})\\b"
                    + "|\\bport\\(s\\):\\s*(\\d{2,5})\\b",
            Pattern.CASE_INSENSITIVE);

    /** How the probe ended. */
    enum Outcome {
        /** The port (or URL, or pattern) answered. */
        READY,
        /** The process exited first. */
        EXITED,
        /** The timeout passed with the process still up and the probe not satisfied. */
        TIMED_OUT,
        /** The timeout passed with the process up and no port known to probe. */
        NO_PORT
    }

    /** What the probe saw: the outcome, the port it ended on ({@code 0} for none), and the URL it answered at. */
    record Result(Outcome outcome, int port, String url) {}

    private final McpApp app;
    private final ExecPlan.Probe declared;
    private final long timeoutMillis;
    private final Clock clock;
    private int port;
    private @Nullable RandomAccessFile reader;
    private long readAt;
    private final StringBuilder partial = new StringBuilder();
    private final @Nullable Pattern pattern;

    /**
     * @param port the port the call named, else {@code [dev] ready}'s, else {@code 0} to learn it
     *     from the app's output
     */
    McpAppReady(McpApp app, ExecPlan.Probe declared, int port, long timeoutMillis, Clock clock) {
        this.app = app;
        this.declared = declared;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        this.clock = clock;
        this.pattern = declared.readyPattern().isEmpty() ? null : Pattern.compile(declared.readyPattern());
    }

    /** The port a {@code [dev] ready} URL names, its scheme's default when it names none; {@code 0} for no URL. */
    static int portOf(String url) {
        if (url.isEmpty()) return 0;
        try {
            URI uri = URI.create(url);
            if (uri.getPort() > 0) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** The port {@code line} says a server listens on; {@code 0} when it says none. */
    static int listensOn(String line) {
        Matcher m = LISTENS.matcher(line);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                String digits = m.group(g);
                if (digits == null) continue;
                int p = Integer.parseInt(digits);
                if (p > 0 && p <= 65535) return p;
            }
        }
        return 0;
    }

    /** True when something on this machine accepts a TCP connection on {@code port}. */
    static boolean answers(int port) {
        for (String host : List.of("127.0.0.1", "::1")) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 500);
                return true;
            } catch (IOException notThere) {
                // not listening on this loopback
            }
        }
        return false;
    }

    /** Wait for the app. Never throws; an interrupt ends the wait as a timeout. */
    Result await() {
        long deadline = clock.millis() + timeoutMillis;
        try (HttpClient http = declared.ready().isEmpty() ? null : client()) {
            while (true) {
                boolean alive = app.alive();
                if (ready(http)) return new Result(Outcome.READY, port, address());
                if (!alive) return new Result(Outcome.EXITED, port, "");
                if (clock.millis() >= deadline) {
                    return new Result(port == 0 && pattern == null ? Outcome.NO_PORT : Outcome.TIMED_OUT, port, "");
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.TIMED_OUT, port, "");
        } finally {
            closeReader();
        }
    }

    /** One look: the new output read, then whichever probe applies. */
    private boolean ready(@Nullable HttpClient http) throws InterruptedException {
        boolean matched = false;
        for (String line : newLines()) {
            if (pattern != null && pattern.matcher(line).find()) matched = true;
            if (port == 0) port = listensOn(line);
        }
        if (pattern != null) return matched;
        if (http != null) return httpAnswers(http, declared.ready());
        return port != 0 && answers(port);
    }

    private String address() {
        if (!declared.ready().isEmpty()) return declared.ready();
        return port == 0 ? "" : "http://localhost:" + port;
    }

    private static HttpClient client() {
        return Http.loopbackClientBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** True when {@code url}, or for {@code localhost} either loopback, answers 2xx/3xx. */
    private static boolean httpAnswers(HttpClient http, String url) throws InterruptedException {
        URI uri = URI.create(url);
        List<String> hosts = "localhost".equalsIgnoreCase(uri.getHost())
                ? List.of("127.0.0.1", "[::1]")
                : List.of(String.valueOf(uri.getHost()));
        for (String host : hosts) {
            String authority = uri.getPort() < 0 ? host : host + ":" + uri.getPort();
            try {
                URI candidate = new URI(uri.getScheme(), authority, uri.getPath(), uri.getQuery(), null);
                HttpRequest request = HttpRequest.newBuilder(candidate)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode();
                if (status >= 200 && status < 400) return true;
            } catch (IOException | URISyntaxException | RuntimeException notYet) {
                // not answering on this address yet
            }
        }
        return false;
    }

    /** The whole lines the app wrote since the last look. */
    private List<String> newLines() {
        Path log = app.log();
        try {
            if (reader == null) reader = new RandomAccessFile(log.toFile(), "r");
            long length = reader.length();
            if (length <= readAt) return List.of();
            byte[] bytes = new byte[(int) Math.min(length - readAt, 1 << 20)];
            reader.seek(readAt);
            reader.readFully(bytes);
            readAt += bytes.length;
            partial.append(new String(bytes, StandardCharsets.UTF_8));
            int end = partial.lastIndexOf("\n");
            if (end < 0) return List.of();
            List<String> lines = partial.substring(0, end).lines().toList();
            partial.delete(0, end + 1);
            return lines;
        } catch (IOException e) {
            return List.of();
        }
    }

    private void closeReader() {
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {
            // read-only; nothing to lose
        }
    }
}
