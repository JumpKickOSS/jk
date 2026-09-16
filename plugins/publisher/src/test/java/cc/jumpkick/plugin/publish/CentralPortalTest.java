// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A stub Portal on loopback: the upload hands out an id, the status walks the states. */
class CentralPortalTest {

    private HttpServer server;
    private URI base;
    private final List<String> requests = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private volatile byte[] uploaded = new byte[0];
    private final AtomicInteger polls = new AtomicInteger();
    private volatile List<String> walk = List.of("PENDING", "VALIDATING", "VALIDATED");
    private volatile String errorsJson = "";

    /** A clock the test moves by hand, so a deadline passes through a method call and never a wait. */
    private static final class TestClock implements Clock {
        private long nanos;

        @Override
        public long millis() {
            return nanos / 1_000_000;
        }

        @Override
        public long nanos() {
            return nanos;
        }

        void advance(Duration d) {
            nanos += d.toNanos();
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/publisher/upload", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            uploaded = exchange.getRequestBody().readAllBytes();
            byte[] id = "dep-1234".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, id.length);
            exchange.getResponseBody().write(id);
            exchange.close();
        });
        server.createContext("/api/v1/publisher/status", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            int n = Math.min(polls.getAndIncrement(), walk.size() - 1);
            String state = walk.get(n);
            String body = "{\"deploymentId\":\"dep-1234\",\"deploymentName\":\"widget-1.0.0\",\"deploymentState\":\""
                    + state + "\"" + ("FAILED".equals(state) ? ",\"errors\":" + errorsJson : "") + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void upload_is_a_bearer_multipart_post_that_returns_the_deployment_id() throws Exception {
        CentralPortal portal = new CentralPortal(base, new RepoCredential.Bearer("tok"), new Http());
        String id = portal.upload(
                "zip".getBytes(StandardCharsets.UTF_8), "widget-1.0.0", CentralPortal.PublishingType.AUTOMATIC);
        assertThat(id).isEqualTo("dep-1234");
        assertThat(requests)
                .containsExactly("POST /api/v1/publisher/upload?name=widget-1.0.0&publishingType=AUTOMATIC");
        assertThat(authorizations).containsExactly("Bearer tok");
        String body = new String(uploaded, StandardCharsets.ISO_8859_1);
        assertThat(body)
                .contains("name=\"bundle\"; filename=\"widget-1.0.0.zip\"")
                .contains("\r\n\r\nzip\r\n--");
    }

    @Test
    void a_basic_credential_is_the_portal_s_base64_user_token() throws Exception {
        assertThat(CentralPortal.bearerToken(new RepoCredential.Basic("name", "secret")))
                .isEqualTo("bmFtZTpzZWNyZXQ=");
        assertThatThrownBy(() -> CentralPortal.bearerToken(RepoCredential.ANONYMOUS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk repo login central");
    }

    @Test
    void the_poll_walks_to_validated_for_a_user_managed_deployment() throws Exception {
        CentralPortal portal = new CentralPortal(base, new RepoCredential.Bearer("tok"), new Http());
        List<Long> sleeps = new ArrayList<>();
        CentralPortal.Status status = portal.awaitTerminal(
                "dep-1234",
                CentralPortal.PublishingType.USER_MANAGED,
                Duration.ofMinutes(1),
                new TestClock(),
                sleeps::add);
        assertThat(status.state()).isEqualTo("VALIDATED");
        assertThat(status.errors()).isEmpty();
        assertThat(polls.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(500L, 1000L);
    }

    @Test
    void an_automatic_deployment_polls_through_validated_to_published() throws Exception {
        walk = List.of("PENDING", "VALIDATED", "PUBLISHING", "PUBLISHED");
        CentralPortal portal = new CentralPortal(base, new RepoCredential.Bearer("tok"), new Http());
        CentralPortal.Status status = portal.awaitTerminal(
                "dep-1234",
                CentralPortal.PublishingType.AUTOMATIC,
                Duration.ofMinutes(1),
                new TestClock(),
                millis -> {});
        assertThat(status.state()).isEqualTo("PUBLISHED");
        assertThat(polls.get()).isEqualTo(4);
    }

    @Test
    void a_failed_deployment_carries_every_error_the_portal_grouped() throws Exception {
        walk = List.of("PENDING", "FAILED");
        errorsJson = "{\"common\":[\"Missing signature for file: widget-1.0.0.pom\"],"
                + "\"pkg:maven/com.example/widget@1.0.0\":[\"Javadocs must be provided but not found in entries\"]}";
        CentralPortal portal = new CentralPortal(base, new RepoCredential.Bearer("tok"), new Http());
        CentralPortal.Status status = portal.awaitTerminal(
                "dep-1234",
                CentralPortal.PublishingType.USER_MANAGED,
                Duration.ofMinutes(1),
                new TestClock(),
                millis -> {});
        assertThat(status.failed()).isTrue();
        assertThat(status.errors())
                .containsExactly(
                        "Missing signature for file: widget-1.0.0.pom",
                        "Javadocs must be provided but not found in entries");
    }

    @Test
    void a_poll_that_never_settles_gives_up_naming_the_deployment_and_its_state() throws Exception {
        walk = List.of("VALIDATING");
        CentralPortal portal = new CentralPortal(base, new RepoCredential.Bearer("tok"), new Http());
        // Every sleep moves the clock, so the deadline passes after a handful of polls, not a wait.
        TestClock clock = new TestClock();
        assertThatThrownBy(() -> portal.awaitTerminal(
                        "dep-1234",
                        CentralPortal.PublishingType.USER_MANAGED,
                        Duration.ofSeconds(3),
                        clock,
                        millis -> clock.advance(Duration.ofMillis(millis))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("dep-1234")
                .hasMessageContaining("VALIDATING");
    }

    @Test
    void the_status_document_is_parsed_defensively() throws Exception {
        assertThat(CentralPortal.parseStatus("{\"deploymentState\":\"PENDING\"}", "d")
                        .errors())
                .isEmpty();
        assertThat(CentralPortal.parseStatus("{\"deploymentState\":\"FAILED\",\"errors\":[\"a\",\"b\"]}", "d")
                        .errors())
                .containsExactly("a", "b");
        assertThat(CentralPortal.parseStatus("{\"deploymentState\":\"FAILED\",\"errors\":\"just one\"}", "d")
                        .errors())
                .containsExactly("just one");
        assertThatThrownBy(() -> CentralPortal.parseStatus("{\"nope\":1}", "d"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("deploymentState");
        assertThatThrownBy(() -> CentralPortal.parseStatus("<html>", "d")).isInstanceOf(IOException.class);
        assertThat(CentralPortal.PublishingType.parse("automatic")).isEqualTo(CentralPortal.PublishingType.AUTOMATIC);
        assertThat(CentralPortal.PublishingType.parse(null)).isEqualTo(CentralPortal.PublishingType.USER_MANAGED);
        assertThatThrownBy(() -> CentralPortal.PublishingType.parse("later"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
