package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The owner card's JSON says phone, and every reader of it follows: the web client's type and the line
 * it renders, and the OpenAPI description of the card.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OwnerCardPhoneAcceptanceTests {

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
    void theCardSaysPhone() throws Exception {
        String json = get("/api/owners/1/card").body();
        assertThat(json).contains("\"phone\":\"6085551023\"").doesNotContain("telephone");
    }

    @Test
    void theClientReadsOnlyWhatTheCardServes() throws Exception {
        String json = get("/api/owners/1/card").body();
        String client = Files.readString(Path.of("client/src/owner-card.ts"));
        Matcher type = Pattern.compile("(?s)interface OwnerCard \\{(.*?)}").matcher(client);
        assertThat(type.find())
                .as("client/src/owner-card.ts still declares OwnerCard")
                .isTrue();
        Matcher member = Pattern.compile("(?m)^\\s*(\\w+)\\??:").matcher(type.group(1));
        int members = 0;
        while (member.find()) {
            members++;
            assertThat(json).as("OwnerCard." + member.group(1)).contains("\"" + member.group(1) + "\":");
        }
        assertThat(members).isEqualTo(5);
        Matcher read = Pattern.compile("\\bcard\\.(\\w+)").matcher(client);
        int reads = 0;
        while (read.find()) {
            reads++;
            assertThat(json).as("card." + read.group(1)).contains("\"" + read.group(1) + "\":");
        }
        assertThat(reads).isGreaterThanOrEqualTo(5);
    }

    @Test
    void theOpenApiCardSaysPhone() throws Exception {
        String api = Files.readString(Path.of("docs/openapi.yaml"));
        Matcher card = Pattern.compile("(?s)\\n    OwnerCard:\\n(.*)").matcher(api);
        assertThat(card.find()).isTrue();
        assertThat(card.group(1)).containsPattern("(?m)^        phone:$").doesNotContain("telephone");
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
