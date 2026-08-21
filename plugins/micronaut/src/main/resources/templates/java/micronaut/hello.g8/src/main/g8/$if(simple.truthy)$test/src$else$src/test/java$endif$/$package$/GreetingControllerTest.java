package $package$;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@MicronautTest
class GreetingControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    GreetingService greetings;

    @Test
    void serviceGreets() {
        assertEquals("Hello, jk!", greetings.greet("jk"));
        assertEquals("Hello, world!", greetings.greet(" "));
    }

    @Test
    void endpointResponds() {
        String body = client.toBlocking().retrieve("/hello?name=jk");
        assertTrue(body.contains("Hello, jk!"));
    }
}
