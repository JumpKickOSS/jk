package $package$;

import io.helidon.config.Config;
import io.helidon.http.Status;
import io.helidon.service.registry.Services;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import jakarta.json.Json;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Greets you in JSON.
 *
 * <pre>
 * curl http://localhost:8080/greet                  # {"message":"Hello World!"}
 * curl http://localhost:8080/greet/Joe              # {"message":"Hello Joe!"}
 * curl -X PUT -H 'Content-Type: application/json' -d '{"greeting":"Howdy"}' \
 *     http://localhost:8080/greet/greeting          # 204; later greetings say Howdy
 * </pre>
 */
public class GreetService implements HttpService {

    private static final JsonBuilderFactory JSON = Json.createBuilderFactory(Map.of());

    /** The greeting: {@code app.greeting} in the configuration until a PUT changes it. */
    private final AtomicReference<String> greeting = new AtomicReference<>();

    GreetService() {
        this(Services.get(Config.class).get("app"));
    }

    GreetService(Config appConfig) {
        greeting.set(appConfig.get("greeting").asString().orElse("Ciao"));
    }

    @Override
    public void routing(HttpRules rules) {
        rules.get("/", this::getDefaultMessageHandler)
                .get("/{name}", this::getMessageHandler)
                .put("/greeting", this::updateGreetingHandler);
    }

    private void getDefaultMessageHandler(ServerRequest request, ServerResponse response) {
        sendResponse(response, "World");
    }

    private void getMessageHandler(ServerRequest request, ServerResponse response) {
        sendResponse(response, request.path().pathParameters().get("name"));
    }

    private void sendResponse(ServerResponse response, String name) {
        String msg = String.format("%s %s!", greeting.get(), name);
        response.send(JSON.createObjectBuilder().add("message", msg).build());
    }

    private void updateGreetingHandler(ServerRequest request, ServerResponse response) {
        JsonObject json = request.content().as(JsonObject.class);
        if (!json.containsKey("greeting")) {
            response.status(Status.BAD_REQUEST_400)
                    .send(JSON.createObjectBuilder()
                            .add("error", "No greeting provided")
                            .build());
            return;
        }
        greeting.set(json.getString("greeting"));
        response.status(Status.NO_CONTENT_204).send();
    }
}
