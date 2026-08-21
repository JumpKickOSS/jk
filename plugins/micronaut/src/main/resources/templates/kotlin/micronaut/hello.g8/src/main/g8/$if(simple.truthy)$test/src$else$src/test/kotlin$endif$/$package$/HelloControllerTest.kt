package $package$

import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@MicronautTest
class HelloControllerTest {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun hello() {
        val body = client.toBlocking().retrieve(HttpRequest.GET<Any>("/hello"))
        assertEquals("Hello from Micronaut", body)
    }
}
