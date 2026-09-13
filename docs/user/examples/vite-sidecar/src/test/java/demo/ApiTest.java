package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ApiTest {

    @Test
    void hello_answers_json_on_the_api_path() throws Exception {
        HttpServer server = Api.start(0);
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI url = URI.create("http://localhost:" + server.getAddress().getPort() + "/api/hello");
            HttpResponse<String> response =
                    client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(
                    "application/json",
                    response.headers().firstValue("Content-Type").orElse(""));
            assertTrue(response.body().contains("\"message\":\"hello from the JVM\""), response.body());
        } finally {
            server.stop(0);
        }
    }
}
