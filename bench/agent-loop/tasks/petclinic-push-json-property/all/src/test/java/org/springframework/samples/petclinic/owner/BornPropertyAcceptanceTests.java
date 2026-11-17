package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The pets endpoint's JSON says born, and the birthday-cards script reads only properties it serves. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BornPropertyAcceptanceTests {

    @LocalServerPort
    int port;

    @Autowired
    RestTemplateBuilder builder;

    private String pets() {
        return builder.baseUri("http://localhost:" + port).build().getForObject("/api/owners/1/pets", String.class);
    }

    @Test
    void theEndpointSaysBorn() {
        assertThat(pets()).contains("\"born\":\"2010-09-07\"").doesNotContain("birthDate");
    }

    @Test
    void theScriptReadsWhatTheEndpointServes() throws Exception {
        String script = Files.readString(Path.of("scripts/birthday-cards.sh"));
        Matcher jq = Pattern.compile("jq -r '([^']*)'").matcher(script);
        assertThat(jq.find())
                .as("the script still formats the endpoint's JSON with jq")
                .isTrue();
        Matcher field = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)").matcher(jq.group(1));
        String json = pets();
        int fields = 0;
        while (field.find()) {
            fields++;
            assertThat(json).as("." + field.group(1)).contains("\"" + field.group(1) + "\":");
        }
        assertThat(fields).isEqualTo(2);
    }
}
