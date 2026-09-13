// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One process's readiness, the same three ways for a sidecar and for the app: an HTTP URL polled
 * for 2xx/3xx, a regex over the output lines the owner feeds in, or — with neither — a stretch
 * alive ({@link #NO_PROBE_ALIVE_MILLIS} for a sidecar, none for the app, which counts as ready
 * once forked). A process that exits first, or a probe that outlives its timeout, is a failure
 * sentence naming the {@code subject} — {@code sidecar `web`} or {@code the app} — so the session
 * reports both in one voice.
 */
final class ReadyProbe {

    /** A sidecar with no probe is ready once it has stayed up this long. */
    static final long NO_PROBE_ALIVE_MILLIS = 1_000;

    private final String subject;
    private final String url;
    private final @Nullable Pattern pattern;
    private final long timeoutMillis;
    private final long aliveMillis;
    private final Process process;
    private final Clock clock;
    private final long startedAt;
    private final CountDownLatch patternSeen = new CountDownLatch(1);

    /**
     * @param probe the process's probe as the plan carries it — the app's {@code appReady}, a
     *     sidecar's {@code probe}; {@link ExecPlan.Probe#NONE} declares none
     * @param aliveMillis how long the process must stay up to count as ready when neither probe is
     *     set — {@link #NO_PROBE_ALIVE_MILLIS} for a sidecar, {@code 0} for the app
     */
    ReadyProbe(String subject, ExecPlan.Probe probe, long aliveMillis, Process process, Clock clock) {
        this.subject = subject;
        this.url = probe.ready();
        this.pattern = probe.readyPattern().isEmpty() ? null : Pattern.compile(probe.readyPattern());
        this.timeoutMillis = probe.readyTimeoutMillis();
        this.aliveMillis = aliveMillis;
        this.process = process;
        this.clock = clock;
        this.startedAt = clock.nanos();
    }

    /** True when a probe is declared at all; a process without one is ready by staying alive. */
    boolean declared() {
        return pattern != null || !url.isEmpty();
    }

    /** One output line, stdout or stderr; the owner reports it before it can count here. */
    void sawLine(String line) {
        if (pattern != null && pattern.matcher(line).find()) patternSeen.countDown();
    }

    /** Wait for the probe. Returns the failure as a printable sentence, or null once ready. */
    @Nullable
    String await() throws InterruptedException {
        long deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if (pattern != null) {
            while (clock.nanos() < deadline) {
                if (patternSeen.await(100, TimeUnit.MILLISECONDS)) return null;
                if (!process.isAlive()) return exitedEarly();
            }
            return timedOut("output never matched /" + pattern.pattern() + "/");
        }
        if (!url.isEmpty()) {
            // HTTP/1.1 only: a dev server that ignores the h2c upgrade would otherwise hang the
            // probe until its timeout, and none of them speak HTTP/2 on plain TCP anyway. Not
            // Http's verbs — a probe must answer in one attempt and take a 3xx as alive — but
            // Http's client builder, so a front door off loopback is reached through the proxy
            // the shell names, and bypassed where no_proxy says so.
            try (HttpClient client = Http.proxiedClientBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()) {
                List<HttpRequest> requests = new ArrayList<>();
                for (URI candidate : readyCandidates(URI.create(url))) {
                    requests.add(Http.proxiedRequest(candidate)
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build());
                }
                while (clock.nanos() < deadline) {
                    if (!process.isAlive()) return exitedEarly();
                    for (HttpRequest request : requests) {
                        try {
                            int status = client.send(request, HttpResponse.BodyHandlers.discarding())
                                    .statusCode();
                            if (status >= 200 && status < 400) return null;
                        } catch (IOException | RuntimeException notYet) {
                            // not listening on this address yet
                        }
                    }
                    Thread.sleep(250);
                }
            }
            return timedOut(url + " never answered 2xx/3xx");
        }
        long aliveUntil = startedAt + TimeUnit.MILLISECONDS.toNanos(aliveMillis);
        while (clock.nanos() < aliveUntil) {
            if (!process.isAlive()) return exitedEarly();
            Thread.sleep(50);
        }
        return null;
    }

    private String exitedEarly() {
        return subject + " exited with " + process.exitValue() + " before it was ready";
    }

    private String timedOut(String what) {
        return subject + " was not ready after " + timeoutMillis / 1000 + " s: " + what;
    }

    /**
     * The addresses a {@code ready} URL is tried on. {@code localhost} becomes both loopbacks:
     * Node binds {@code ::1} alone on a dual-stack host while the JDK client resolves the name to
     * {@code 127.0.0.1}, and a probe that only tried one of them would call a serving Vite "not
     * ready" for the whole timeout.
     */
    static List<URI> readyCandidates(URI url) {
        String host = url.getHost();
        if (host == null || !host.equalsIgnoreCase("localhost")) return List.of(url);
        return List.of(withHost(url, "127.0.0.1"), withHost(url, "[::1]"));
    }

    private static URI withHost(URI url, String host) {
        String authority = url.getPort() < 0 ? host : host + ":" + url.getPort();
        try {
            return new URI(url.getScheme(), authority, url.getPath(), url.getQuery(), url.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
