package $package$;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import io.helidon.http.Status;
import io.helidon.webclient.api.ClientResponseTyped;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.http1.Http1ClientResponse;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The application's routes on a server of their own, on a free port, over an in-memory H2. */
@ServerTest
class MainTest {

    private static final JsonBuilderFactory JSON = Json.createBuilderFactory(Map.of());

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
        ClientResponseTyped<JsonObject> response = client.get("/greet").request(JsonObject.class);
        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity().getString("message"), is("Hello World!"));
    }

    @Test
    void lists_every_pokemon() {
        ClientResponseTyped<JsonArray> response = client.get("/db/pokemon").request(JsonArray.class);
        assertThat(response.status(), is(Status.OK_200));
        assertThat(names(response.entity()), is(seeded("/pokemons.json")));
    }

    @Test
    void lists_every_type() {
        ClientResponseTyped<JsonArray> response = client.get("/db/type").request(JsonArray.class);
        assertThat(response.status(), is(Status.OK_200));
        assertThat(names(response.entity()), is(seeded("/pokemon-types.json")));
    }

    @Test
    void gets_a_pokemon_by_id() {
        ClientResponseTyped<JsonObject> response = client.get("/db/pokemon/2").request(JsonObject.class);
        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity().getString("NAME"), is("Charmander"));
    }

    @Test
    void gets_a_pokemon_by_name() {
        ClientResponseTyped<JsonObject> response =
                client.get("/db/pokemon/name/Squirtle").request(JsonObject.class);
        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity().getInt("ID"), is(3));
    }

    @Test
    void adds_renames_and_deletes_a_pokemon() {
        JsonObject rattata = JSON.createObjectBuilder()
                .add("id", 7)
                .add("name", "Rattata")
                .add("idType", 1)
                .build();
        assertThat(client.post("/db/pokemon").submit(rattata, String.class).status(), is(Status.CREATED_201));

        JsonObject raticate = JSON.createObjectBuilder()
                .add("id", 7)
                .add("name", "Raticate")
                .add("idType", 2)
                .build();
        assertThat(client.put("/db/pokemon").submit(raticate, String.class).status(), is(Status.OK_200));

        assertThat(client.delete("/db/pokemon/7").request(String.class).status(), is(Status.NO_CONTENT_204));
        assertThat(client.get("/db/pokemon/7").request(String.class).status(), is(Status.NOT_FOUND_404));
    }

    @Test
    void the_test_profile_is_on() {
        // [helidon] runs tests with helidon.config.profile=test: application-test.yaml overlays
        // application.yaml, which is what points the database client at an in-memory H2.
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

    /** The NAME column of each row; the database client upper-cases column names. */
    private static List<String> names(JsonArray rows) {
        return rows.stream().map(e -> e.asJsonObject().getString("NAME")).toList();
    }

    /** The names in one of the seed files PokemonService loads. */
    private static List<String> seeded(String resource) {
        InputStream in = Objects.requireNonNull(MainTest.class.getResourceAsStream(resource), resource);
        try (JsonReader reader = Json.createReader(in)) {
            return reader.readArray().stream()
                    .map(e -> e.asJsonObject().getString("name"))
                    .toList();
        }
    }
}
