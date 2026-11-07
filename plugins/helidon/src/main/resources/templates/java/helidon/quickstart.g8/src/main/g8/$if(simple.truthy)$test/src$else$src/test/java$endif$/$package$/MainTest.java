package $package$;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

$if(media_json.truthy)$
import io.helidon.common.Api;
$endif$
import io.helidon.http.Status;
$if(media_json.truthy)$
import io.helidon.json.JsonObject;
$endif$
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.http1.Http1ClientResponse;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
$if(media_jsonp.truthy)$
import jakarta.json.JsonObject;
$endif$
import org.junit.jupiter.api.Test;

/** The application's routes on a server of their own, on a free port. */
$if(media_json.truthy)$
@SuppressWarnings(Api.SUPPRESS_PREVIEW) // Helidon JSON is a preview API in Helidon 27
$endif$
@ServerTest
class MainTest {

    private final Http1Client client;

    MainTest(Http1Client client) {
        this.client = client;
    }

    @SetUpRoute
    static void routing(HttpRouting.Builder builder) {
        Main.routing(builder);
    }

    @Test
    void greets_the_world() {
        try (Http1ClientResponse response = client.get("/greet").request()) {
            assertThat(response.status(), is(Status.OK_200));
$if(media_jsonp.truthy)$
            assertThat(response.as(JsonObject.class).getString("message"), is("Hello World!"));
$else$
            assertThat(response.as(JsonObject.class).stringValue("message", ""), is("Hello World!"));
$endif$
        }
    }

    @Test
    void greets_by_name() {
        try (Http1ClientResponse response = client.get("/greet/Joe").request()) {
            assertThat(response.status(), is(Status.OK_200));
$if(media_jsonp.truthy)$
            assertThat(response.as(JsonObject.class).getString("message"), is("Hello Joe!"));
$else$
            assertThat(response.as(JsonObject.class).stringValue("message", ""), is("Hello Joe!"));
$endif$
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

    @Test
    void no_deadlock() {
        try (Http1ClientResponse response = client.get("/observe/health/live/deadlock").request()) {
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
$if(tracing.truthy)$

    @Test
    void the_traced_route_answers() {
        try (Http1ClientResponse response = client.get("/traced").request()) {
            assertThat(response.status(), is(Status.OK_200));
        }
    }
$endif$
}
