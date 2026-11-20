// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Guest;
import cc.jumpkick.host.time.Clock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The front door's proxy behind the engine's own token gate: a request under {@code /engines/<id>/}
 * that presented the front door's token reaches the paired engine with that engine's token and never
 * the front door's; one that did not is refused before anything is sent, or, for a page, sent with no
 * token at all; and an event stream flows through frame by frame. The guest is a stub that records
 * what reached it. Fixture in {@link HttpEngineServerHarness}.
 */
@Tag("integration")
class FrontDoorProxyTest extends HttpEngineServerHarness {

    private static final String GUEST_ID = "caf4beecf11d";
    private static final String GUEST_TOKEN = "guest-token-7f3a";

    @TempDir
    Path guests;

    private HttpServer guest;

    /** What reached the guest: one line per request, its path, query and credential-bearing headers. */
    private final List<String> reached = new CopyOnWriteArrayList<>();

    private final CountDownLatch secondFrame = new CountDownLatch(1);

    private volatile int statusAnswer = 200;

    /** Request bodies that reached the guest, by size. */
    private final List<Integer> bodies = new CopyOnWriteArrayList<>();

    /** The {@code Authorization} of each identity request that reached the guest. */
    private final List<String> identityAsked = new CopyOnWriteArrayList<>();

    /** Where the guest redirects a request for its report directory {@code /report/<token>/r1[/]}. */
    private volatile String reportRedirect = "";

    /** A header the guest adds to every answer, {@code name: value}; none when blank. */
    private volatile String extraHeader = "";

    @BeforeEach
    void startGuestAndDoor() throws IOException {
        guest = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int guestPort = guest.getAddress().getPort();
        guest.createContext("/api/identity", ex -> {
            identityAsked.add(
                    ex.getRequestMethod() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            send(ex, 200, "{\"id\":\"" + GUEST_ID + "\",\"label\":\"WSL Ubuntu\",\"port\":" + guestPort + "}");
        });
        guest.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            if (in.length > 0) bodies.add(in.length);
            if (!extraHeader.isBlank()) {
                String[] h = extraHeader.split(": ", 2);
                ex.getResponseHeaders().set(h[0], h[1]);
            }
            reached.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                    + " q=" + ex.getRequestURI().getRawQuery()
                    + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
                    + " referer=" + ex.getRequestHeaders().getFirst("Referer")
                    + " cookie=" + ex.getRequestHeaders().getFirst("Cookie"));
            String path = ex.getRequestURI().getPath();
            String dir = "/report/" + GUEST_TOKEN + "/r1";
            if (path.equals(dir) || path.equals(dir + "/")) {
                ex.getResponseHeaders().set("Location", reportRedirect.replace("<port>", "" + guestPort));
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (path.equals("/api/events")) {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write("event: status\ndata: {\"n\":1}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // The second frame waits until the client has read the first: pass-through, not buffering.
                    secondFrame.await(10, TimeUnit.SECONDS);
                    out.write("event: status\ndata: {\"n\":2}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (path.startsWith("/api/")) {
                boolean ok =
                        ("Bearer " + GUEST_TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"));
                send(ex, ok ? statusAnswer : 401, ok ? "{\"engine\":\"guest\"}" : "no");
                return;
            }
            send(ex, 200, "<html>guest page</html>");
        });
        guest.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        guest.start();

        GuestPairing.write(guests, new GuestPairing.Pair(GUEST_ID, guestPort, GUEST_TOKEN));
        EngineIdentity self = new EngineIdentity("90cec5c68a24", "windows", Guest.NONE, "box", "C:/me/.jk", "1.0");
        server.setIdentity(self);
        server.setFrontDoor(new FrontDoor(() -> self, server::port, guests, List.of(guestPort)));
    }

    @AfterEach
    void stopGuest() {
        guest.stop(0);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpResponse<String> send(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1)));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String prefix() {
        return "/engines/" + GUEST_ID;
    }

    @Test
    void the_engines_are_listed_with_the_front_doors_token_and_without_an_epoch() throws Exception {
        HttpResponse<String> list = send("/api/engines", "Authorization", "Bearer " + token());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body())
                .contains("\"id\":\"90cec5c68a24\"")
                .contains("\"id\":\"" + GUEST_ID + "\"")
                .contains("\"paired\":true")
                .doesNotContain(GUEST_TOKEN);
        assertThat(send("/api/engines").statusCode()).isEqualTo(401);
    }

    @Test
    void an_api_request_reaches_the_engine_with_its_token_and_never_the_front_doors() throws Exception {
        HttpResponse<String> ok = send(
                prefix() + "/api/status",
                "Authorization",
                "Bearer " + token(),
                "Referer",
                "http://127.0.0.1/report/" + token() + "/r1/",
                "Cookie",
                "c=1");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("{\"engine\":\"guest\"}");
        assertThat(reached)
                .containsExactly("GET /api/status q=null auth=Bearer " + GUEST_TOKEN + " referer=null cookie=null");
        assertThat(String.join("\n", reached)).doesNotContain(token());
    }

    @Test
    void a_request_without_the_front_doors_token_is_refused_before_anything_reaches_the_engine() throws Exception {
        assertThat(send(prefix() + "/api/status").statusCode()).isEqualTo(401);
        assertThat(send(prefix() + "/api/status", "Authorization", "Bearer " + GUEST_TOKEN)
                        .statusCode())
                .as("the guest's own token is not the front door's")
                .isEqualTo(401);
        assertThat(send(prefix() + "/api/status?access_token=" + token()).statusCode())
                .as("a query token is taken for the event stream only")
                .isEqualTo(401);
        assertThat(send(prefix() + "/mcp").statusCode()).isEqualTo(401);
        assertThat(send(prefix() + "/report/wrong/r1/index.html").statusCode()).isEqualTo(401);
        assertThat(send(prefix() + "/api%3F/x").statusCode())
                .as("a path the engine would route otherwise")
                .isEqualTo(404);
        assertThat(send(prefix() + "/x/%2E%2E/api/status").statusCode()).isEqualTo(404);
        assertThat(reached).isEmpty();
    }

    @Test
    void a_page_reaches_the_engine_with_no_token_at_all() throws Exception {
        HttpResponse<String> page =
                send(prefix() + "/index.html?access_token=" + token(), "Authorization", "Bearer " + token());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).isEqualTo("<html>guest page</html>");
        assertThat(reached).containsExactly("GET /index.html q=null auth=null referer=null cookie=null");

        HttpResponse<String> bare = send(prefix());
        assertThat(bare.statusCode()).as("relative links need the slash").isEqualTo(308);
        assertThat(bare.headers().firstValue("Location")).contains(prefix() + "/");
    }

    @Test
    void a_coverage_report_takes_the_front_doors_token_in_its_path_and_reaches_the_engine_with_its_own()
            throws Exception {
        assertThat(send(prefix() + "/report/" + token() + "/r1/index.html").statusCode())
                .isEqualTo(200);
        assertThat(reached)
                .containsExactly(
                        "GET /report/" + GUEST_TOKEN + "/r1/index.html q=null auth=null referer=null cookie=null");
    }

    @Test
    void the_event_stream_flows_through_frame_by_frame_with_the_engines_token_in_its_query() throws Exception {
        HttpResponse<InputStream> stream = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + prefix().substring(1) + "/api/events?access_token="
                                + token() + "&lastEventId=7"))
                        .timeout(Duration.ofSeconds(20))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("Content-Type")).contains("text/event-stream");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            assertThat(in.readLine()).isEqualTo("event: status");
            assertThat(in.readLine()).isEqualTo("data: {\"n\":1}");
            assertThat(server.liveEventStreams())
                    .as("a proxied stream draws on the stream budget, not the request one it would hold for its life")
                    .isEqualTo(1);
            secondFrame.countDown();
            assertThat(in.readLine()).isEmpty();
            assertThat(in.readLine()).isEqualTo("event: status");
            assertThat(in.readLine()).isEqualTo("data: {\"n\":2}");
        }
        assertThat(reached)
                .containsExactly("GET /api/events q=access_token=" + GUEST_TOKEN + "&lastEventId=7 auth=Bearer "
                        + GUEST_TOKEN + " referer=null cookie=null");
    }

    @Test
    void an_engine_refusing_its_paired_token_is_a_bad_gateway_not_a_bad_front_door_token() throws Exception {
        statusAnswer = 401;
        HttpResponse<String> r = send(prefix() + "/api/status", "Authorization", "Bearer " + token());
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void an_engine_nobody_paired_or_that_answers_as_another_is_not_proxied() throws Exception {
        assertThat(send("/engines/0123456789ab/api/status", "Authorization", "Bearer " + token())
                        .statusCode())
                .isEqualTo(404);
        GuestPairing.write(
                guests, new GuestPairing.Pair("0123456789ab", guest.getAddress().getPort(), "other"));
        assertThat(send("/engines/0123456789ab/api/status", "Authorization", "Bearer " + token())
                        .statusCode())
                .as("the port answers as " + GUEST_ID)
                .isEqualTo(502);
        assertThat(reached).isEmpty();
    }

    @Test
    void a_guest_takes_the_front_doors_control_messages_with_its_own_token_and_no_epoch() throws Exception {
        FrontDoorRole role = new FrontDoorRole(
                new FrontDoorRole.Seat() {
                    @Override
                    public boolean take() {
                        return true;
                    }

                    @Override
                    public void release() {}

                    @Override
                    public FrontDoorRole.Reach verify() {
                        return FrontDoorRole.Reach.REACHABLE;
                    }
                },
                Clock.SYSTEM,
                () -> 0,
                line -> {});
        server.setFrontDoorRole(role);
        HttpResponse<String> present = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/front-door/present"))
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(present.statusCode()).isEqualTo(200);
        HttpResponse<String> stepAside = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/front-door/step-aside"))
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(stepAside.body()).isEqualTo("{\"stepped\":false}");
        HttpResponse<String> anonymous = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/front-door/step-aside"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        HttpResponse<String> queryToken = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/front-door/step-aside?access_token=" + token()))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(queryToken.statusCode()).as("bearer only").isEqualTo(401);
    }

    @Test
    void a_front_door_only_listener_sends_its_root_to_its_engines_dashboard_and_echoes_a_nonce() throws Exception {
        server.setFrontDoorHome(GUEST_ID);
        server.setIdentityNonce("n-42");
        HttpResponse<String> root = send("/");
        assertThat(root.statusCode()).isEqualTo(302);
        assertThat(root.headers().firstValue("Location")).contains("/engines/" + GUEST_ID + "/");
        assertThat(send("/api/identity").body()).contains("\"nonce\":\"n-42\"");
    }

    @Test
    void a_report_redirect_lands_under_the_front_door_with_its_token_never_the_engines() throws Exception {
        String entry = prefix() + "/report/" + token() + "/r1/lib/index.html";
        Map<String, String> forms = new LinkedHashMap<>();
        forms.put("/report/" + GUEST_TOKEN + "/r1/lib/index.html", "/r1/");
        forms.put("http://127.0.0.1:<port>/report/" + GUEST_TOKEN + "/r1/lib/index.html", "/r1/");
        forms.put("./lib/index.html", "/r1/");
        forms.put("r1/lib/index.html", "/r1");
        for (Map.Entry<String, String> form : forms.entrySet()) {
            reportRedirect = form.getKey();
            HttpResponse<String> r = send(prefix() + "/report/" + token() + form.getValue());
            assertThat(r.statusCode()).as(form.getKey()).isEqualTo(302);
            assertThat(r.headers().map().toString()).as(form.getKey()).doesNotContain(GUEST_TOKEN);
            String location = r.headers().firstValue("Location").orElseThrow();
            assertThat(URI.create(baseUrl + prefix().substring(1) + "/report/" + token() + form.getValue())
                            .resolve(location)
                            .getRawPath())
                    .as(form.getKey())
                    .isEqualTo(entry);
        }
        HttpResponse<String> landed = send(entry);
        assertThat(landed.statusCode())
                .as("the front door takes the redirect's target")
                .isEqualTo(200);
        assertThat(reached).last().asString().startsWith("GET /report/" + GUEST_TOKEN + "/r1/lib/index.html ");
    }

    @Test
    void a_redirect_off_the_engine_or_a_header_with_its_token_is_a_bad_gateway() throws Exception {
        for (String off : List.of(
                "http://example.com/x",
                "//example.com/x",
                "http://127.0.0.1:" + (guest.getAddress().getPort() ^ 1) + "/x",
                "../../../../x",
                "/report/" + GUEST_TOKEN)) {
            reportRedirect = off;
            HttpResponse<String> r = send(prefix() + "/report/" + token() + "/r1/");
            assertThat(r.statusCode()).as(off).isEqualTo(502);
            assertThat(r.headers().firstValue("Location")).as(off).isEmpty();
            assertThat(r.body()).doesNotContain(GUEST_TOKEN);
        }
        extraHeader = "X-Debug: token=" + GUEST_TOKEN;
        HttpResponse<String> page = send(prefix() + "/index.html");
        assertThat(page.statusCode()).isEqualTo(502);
        assertThat(page.headers().map().toString()).doesNotContain(GUEST_TOKEN);
    }

    @Test
    void a_page_or_report_takes_only_reads_and_is_refused_before_the_engine_is_asked() throws Exception {
        for (String path : List.of(prefix() + "/index.html", prefix() + "/report/" + token() + "/r1/index.html")) {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1)))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1024]))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(path).isEqualTo(405);
            assertThat(r.headers().firstValue("Allow")).as(path).contains("GET, HEAD");
        }
        assertThat(reached).isEmpty();
        assertThat(identityAsked).as("not even the pairing's identity").isEmpty();
    }

    @Test
    void a_body_past_the_paths_own_cap_is_a_413_and_one_under_it_is_forwarded() throws Exception {
        HttpResponse<String> big = post("/api/build", HttpRequests.MAX_BODY_BYTES + 1);
        assertThat(big.statusCode()).isEqualTo(413);
        assertThat(big.body()).contains("\"maxBytes\":" + HttpRequests.MAX_BODY_BYTES);
        assertThat(reached).isEmpty();
        assertThat(identityAsked).isEmpty();

        assertThat(post("/api/build", 1024).statusCode()).isEqualTo(200);
        assertThat(post("/api/project/file", HttpRequests.MAX_BODY_BYTES * 4).statusCode())
                .as("a file save takes the direct endpoint's larger cap")
                .isEqualTo(200);
        assertThat(post("/api/project/file", HttpProjectApi.MAX_FILE_PUT_BODY_BYTES + 1)
                        .statusCode())
                .isEqualTo(413);
        assertThat(bodies).containsExactly(1024, HttpRequests.MAX_BODY_BYTES * 4);
    }

    @Test
    void the_engines_identity_is_read_without_a_token_and_sent_none() throws Exception {
        HttpResponse<String> id = send(prefix() + "/api/identity");
        assertThat(id.statusCode()).isEqualTo(200);
        assertThat(id.body()).contains("\"id\":\"" + GUEST_ID + "\"");
        assertThat(send(prefix() + "/api/identity", "Authorization", "Bearer " + token())
                        .statusCode())
                .isEqualTo(200);
        assertThat(identityAsked).last().isEqualTo("GET auth=null");
        assertThat(identityAsked).allMatch(asked -> !asked.contains(GUEST_TOKEN));
        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + prefix().substring(1) + "/api/identity"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode())
                .as("only its read is open, as on the engine itself")
                .isEqualTo(401);
    }

    /** A {@code PUT} for the file save, else a {@code POST}, of {@code size} bytes with the front door's token. */
    private HttpResponse<String> post(String apiPath, int size) throws Exception {
        String method = apiPath.equals("/api/project/file") ? "PUT" : "POST";
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + prefix().substring(1) + apiPath))
                        .header("Authorization", "Bearer " + token())
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(new byte[size]))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
