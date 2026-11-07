package $package$;

import io.helidon.common.Api;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Configuration;
import io.helidon.http.BadRequestException;
import io.helidon.http.Http;
import io.helidon.http.Status;
$if(media_json.truthy)$
import io.helidon.json.JsonObject;
$endif$
$if(metrics.truthy)$
import io.helidon.metrics.api.Metrics;
$endif$
import io.helidon.service.registry.Service;
$if(tracing.truthy)$
import io.helidon.tracing.Tracing;
$endif$
import io.helidon.webserver.http.RestServer;
$if(media_jsonp.truthy)$
import jakarta.json.Json;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import java.util.Map;
$endif$
import java.util.concurrent.atomic.AtomicReference;

/**
 * Greets you in JSON. The annotations are the whole wiring: the processors generate this
 * endpoint's routing, and the service registry registers it with the web server.
 *
 * <pre>
 * curl http://localhost:8080/greet                  # {"message":"Hello World!"}
 * curl http://localhost:8080/greet/Joe              # {"message":"Hello Joe!"}
 * curl -X PUT -H 'Content-Type: application/json' -d '{"greeting":"Howdy"}' \
 *     http://localhost:8080/greet/greeting          # 204; later greetings say Howdy
 * </pre>
 */
@SuppressWarnings(Api.SUPPRESS_PREVIEW) // Helidon Declarative is a preview feature in Helidon 27
@RestServer.Endpoint
@Http.Path("/greet")
@Service.Singleton
$if(tracing.truthy)$
@Tracing.Traced
$endif$
class GreetEndpoint {

$if(media_jsonp.truthy)$
    private static final JsonBuilderFactory JSON = Json.createBuilderFactory(Map.of());

$endif$
    /** The greeting: {@code app.greeting} in the configuration until a PUT changes it. */
    private final AtomicReference<String> greeting = new AtomicReference<>();

    @Service.Inject
    GreetEndpoint(@Configuration.Value("\${app.greeting:Ciao}") String greeting) {
        this.greeting.set(greeting);
    }

    @Http.GET
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
$if(metrics.truthy)$
    @Metrics.Counted
$endif$
    JsonObject greetTheWorld() {
        return message("World");
    }

    @Http.GET
    @Http.Path("/{name}")
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    JsonObject greet(@Http.PathParam("name") String name) {
        return message(name);
    }

    @Http.PUT
    @Http.Path("/greeting")
    @Http.Consumes(MediaTypes.APPLICATION_JSON_VALUE)
    @RestServer.Status(Status.NO_CONTENT_204_CODE)
    void updateGreeting(@Http.Entity JsonObject json) {
$if(media_jsonp.truthy)$
        if (!json.containsKey("greeting")) {
            throw new BadRequestException("No greeting provided");
        }
        greeting.set(json.getString("greeting"));
$else$
        greeting.set(json.stringValue("greeting")
                .orElseThrow(() -> new BadRequestException("No greeting provided")));
$endif$
    }

    private JsonObject message(String name) {
        String msg = String.format("%s %s!", greeting.get(), name);
$if(media_jsonp.truthy)$
        return JSON.createObjectBuilder().add("message", msg).build();
$else$
        return JsonObject.builder().set("message", msg).build();
$endif$
    }
}
