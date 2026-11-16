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

/** Find Owners lives at /owners/search, and the smoke script and the menu request what the app serves. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FindRouteAcceptanceTests {

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
    void findOwnersIsAtTheNewAddress() {
        ResponseEntity<String> page = get("/owners/search");
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).contains("Find Owners");
    }

    @Test
    void everyPageTheSmokeScriptRequestsIsServed() throws Exception {
        String script = Files.readString(Path.of("scripts/smoke.sh"));
        Matcher m = Pattern.compile("http://localhost:8080(/[^\\s\"']*)").matcher(script);
        List<String> paths = new ArrayList<>();
        while (m.find()) {
            paths.add(m.group(1));
        }
        assertThat(paths).contains("/owners/search").doesNotContain("/owners/find");
        for (String p : paths) {
            assertThat(get(p).getStatusCode().value()).as(p).isBetween(200, 299);
        }
    }

    @Test
    void theMenuLinksToTheNewAddress() {
        String page = get("/").getBody();
        assertThat(page).contains("href=\"/owners/search\"").doesNotContain("href=\"/owners/find\"");
    }
}
