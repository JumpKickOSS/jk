package $package$;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** The app on a random port, driven over plain HTTP the way a browser would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTest {

    @Autowired
    private Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void helloAnswersUnderApi() throws Exception {
        HttpResponse<String> res = get("/api/hello?name=jk", "application/json");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Hello, jk!");
    }

    @Test
    void unknownApiPathIsNotFoundNotAPage() throws Exception {
        HttpResponse<String> res = get("/api/nope", "text/html");
        assertThat(res.statusCode()).isEqualTo(404);
    }

    @Test
    void clientRouteGetsTheShell() throws Exception {
        assumeTrue(
                getClass().getResource("/static/index.html") != null,
                "the SPA bundle is not built: npm ci && npm run build in web/");
        HttpResponse<String> res = get("/some/client/route", "text/html");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type"))
                .hasValueSatisfying(ct -> assertThat(ct).startsWith("text/html"));
        assertThat(res.body()).contains("<div id=\"root\">");
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        URI uri = URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
        HttpRequest req =
                HttpRequest.newBuilder(uri).header("Accept", accept).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
