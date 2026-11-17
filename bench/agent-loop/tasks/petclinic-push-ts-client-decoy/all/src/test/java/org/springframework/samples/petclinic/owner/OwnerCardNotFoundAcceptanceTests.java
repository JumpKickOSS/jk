package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * An owner the card cannot find is a 404, and nothing a client reads changes: the card's JSON, the
 * web client and the OpenAPI description (which already documents the 404) stay as they were.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OwnerCardNotFoundAcceptanceTests {

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUnknownOwnerIsNotFound() throws Exception {
        assertThat(get("/api/owners/9999/card").statusCode()).isEqualTo(404);
    }

    @Test
    void aKnownOwnersCardIsUnchanged() throws Exception {
        HttpResponse<String> card = get("/api/owners/1/card");
        assertThat(card.statusCode()).isEqualTo(200);
        assertThat(card.body())
                .contains("\"telephone\":\"6085551023\"")
                .contains("\"firstName\":\"George\"")
                .contains("\"pets\":1");
    }

    @Test
    void theClientAndTheOpenApiFileAreUntouched() throws Exception {
        assertThat(sha256("client/src/owner-card.ts"))
                .isEqualTo("fdf203821285d86f910a0a510e3bf0ce0fea44e79762deff43f2fddc5982402b");
        assertThat(sha256("docs/openapi.yaml"))
                .isEqualTo("acd13a69c93292e4b5ebc7221c034349b425fe37a7f527a75bb4d88957d5e010");
    }

    private static String sha256(String file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file))));
    }

    /**
     * The client's own test (client/test/owner-card.test.ts) against this server, where Node.js 22.6 or
     * later is installed; without node the text checks above stand alone.
     */
    @Test
    void theClientsTestPassesAgainstTheServerWhereNodeRuns() throws Exception {
        Process probe;
        try {
            probe = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException noNode) {
            Assumptions.abort("node is not installed");
            return;
        }
        probe.waitFor();
        Path log = Files.createTempFile("owner-card-node", ".log");
        ProcessBuilder pb = new ProcessBuilder(
                        "node", "--experimental-strip-types", "--no-warnings", "--test", "test/owner-card.test.ts")
                .directory(Path.of("client").toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        pb.environment().put("PETCLINIC_URL", "http://localhost:" + port);
        Process node = pb.start();
        boolean ended = node.waitFor(120, TimeUnit.SECONDS);
        if (!ended) node.destroyForcibly();
        String out = Files.readString(log);
        assertThat(ended).as(out).isTrue();
        assertThat(node.exitValue()).as(out).isZero();
    }
}
