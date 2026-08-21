package $package$;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GreetingResourceTest {

    @Test
    void hello_endpoint() {
        given().when().get("/hello").then().statusCode(200).body(is("Hello from Quarkus"));
    }
}
