package $package$

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment

/** The app on a random port, driven over plain HTTP the way a browser would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTest {

    @Autowired lateinit var env: Environment

    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun helloAnswersUnderApi() {
        val res = get("/api/hello?name=jk", "application/json")
        assertThat(res.statusCode()).isEqualTo(200)
        assertThat(res.body()).contains("Hello, jk!")
    }

    @Test
    fun unknownApiPathIsNotFoundNotAPage() {
        val res = get("/api/nope", "text/html")
        assertThat(res.statusCode()).isEqualTo(404)
    }

    @Test
    fun clientRouteGetsTheShell() {
        assumeTrue(
            javaClass.getResource("/static/index.html") != null,
            "the SPA bundle is not built: npm ci && npm run build in web/",
        )
        val res = get("/some/client/route", "text/html")
        assertThat(res.statusCode()).isEqualTo(200)
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/html")
        assertThat(res.body()).contains("<div id=\"root\">")
    }

    private fun get(path: String, accept: String): HttpResponse<String> {
        val uri = URI.create("http://localhost:" + env.getProperty("local.server.port") + path)
        val req = HttpRequest.newBuilder(uri).header("Accept", accept).GET().build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
