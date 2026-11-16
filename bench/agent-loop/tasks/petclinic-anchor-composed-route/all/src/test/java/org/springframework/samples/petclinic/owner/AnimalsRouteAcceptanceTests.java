package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;

/** The owner pets endpoint is .../animals, and the roster script requests what the app serves. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AnimalsRouteAcceptanceTests {

    @LocalServerPort
    int port;

    @Autowired
    RestTemplateBuilder builder;

    private ResponseEntity<String> get(String path) {
        try {
            return builder.baseUri("http://localhost:" + port)
                    .build()
                    .exchange(RequestEntity.get(path).build(), String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        }
    }

    @Test
    void theEndpointIsAnimals() {
        ResponseEntity<String> page = get("/api/owners/1/animals");
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Leo");
    }

    @Test
    void everyRequestTheRosterScriptMakesIsServed() throws Exception {
        String script = Files.readString(Path.of("scripts/pet-roster.sh")).replace("$owner", "1");
        Matcher m = Pattern.compile("http://localhost:8080(/[^\\s\"']*)").matcher(script);
        List<String> paths = new ArrayList<>();
        while (m.find()) {
            paths.add(m.group(1));
        }
        assertThat(paths).hasSize(2);
        for (String p : paths) {
            assertThat(p).as(p).endsWith("/animals");
            assertThat(get(p).getStatusCode().value()).as(p).isEqualTo(200);
        }
    }
}
