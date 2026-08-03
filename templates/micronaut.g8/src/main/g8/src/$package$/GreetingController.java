package $package$;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import java.util.Map;

@Controller("/hello")
public class GreetingController {

    private final GreetingService greetings;

    GreetingController(GreetingService greetings) {
        this.greetings = greetings;
    }

    @Get(produces = MediaType.APPLICATION_JSON)
    public Map<String, String> hello(@QueryValue(defaultValue = "world") String name) {
        return Map.of("message", greetings.greet(name));
    }
}
