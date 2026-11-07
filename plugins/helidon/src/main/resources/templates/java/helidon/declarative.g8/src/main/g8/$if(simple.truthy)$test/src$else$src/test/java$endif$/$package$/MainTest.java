package $package$;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import io.helidon.common.Api;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.Status;
$if(media_json.truthy)$
import io.helidon.json.JsonObject;
$endif$
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.http1.Http1ClientResponse;
import io.helidon.webserver.testing.junit5.ServerTest;
$if(media_jsonp.truthy)$
import jakarta.json.Json;
import jakarta.json.JsonObject;
$endif$
import org.junit.jupiter.api.Test;

/** The application, started from the service registry on a free port. */
@SuppressWarnings(Api.SUPPRESS_PREVIEW) // Helidon Declarative is a preview feature in Helidon 27
@ServerTest
class MainTest {

    private final Http1Client client;

    MainTest(Http1Client client) {
        this.client = client;
    }

    @Test
    void greets_the_world() {
        assertThat(message("/greet"), is("Hello World!"));
    }

    @Test
    void greets_by_name() {
        assertThat(message("/greet/Joe"), is("Hello Joe!"));
    }

    @Test
    void a_put_changes_the_greeting() {
$if(media_jsonp.truthy)$
        JsonObject hola = Json.createObjectBuilder().add("greeting", "Hola").build();
        JsonObject hello = Json.createObjectBuilder().add("greeting", "Hello").build();
$else$
        JsonObject hola = JsonObject.builder().set("greeting", "Hola").build();
        JsonObject hello = JsonObject.builder().set("greeting", "Hello").build();
$endif$
        try (Http1ClientResponse response = client.put("/greet/greeting")
                .contentType(MediaTypes.APPLICATION_JSON)
                .submit(hola)) {
            assertThat(response.status(), is(Status.NO_CONTENT_204));
        }
        try {
            assertThat(message("/greet"), is("Hola World!"));
        } finally {
            client.put("/greet/greeting")
                    .contentType(MediaTypes.APPLICATION_JSON)
                    .submit(hello)
                    .close();
        }
    }

    @Test
    void the_test_profile_is_on() {
        // [helidon] runs tests with helidon.config.profile=test: an application-test.yaml in the test
        // resources would overlay application.yaml here.
        assertThat(System.getProperty("helidon.config.profile"), is("test"));
    }
$if(health.truthy)$

    @Test
    void health_is_up() {
        try (Http1ClientResponse response = client.get("/observe/health").request()) {
            assertThat(response.status(), is(Status.NO_CONTENT_204));
        }
    }
$endif$
$if(metrics.truthy)$

    @Test
    void metrics_are_served() {
        try (Http1ClientResponse response = client.get("/observe/metrics").request()) {
            assertThat(response.status(), is(Status.OK_200));
        }
    }
$endif$

    private String message(String path) {
        try (Http1ClientResponse response = client.get(path).request()) {
            assertThat(response.status(), is(Status.OK_200));
$if(media_jsonp.truthy)$
            return response.as(JsonObject.class).getString("message");
$else$
            return response.as(JsonObject.class).stringValue("message", "");
$endif$
        }
    }
}
