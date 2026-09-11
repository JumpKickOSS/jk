package $package$.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The API lives under {@code /api}: the one prefix the SPA fallback never answers for. */
@RestController
@RequestMapping("/api")
public class HelloController {

    public record Greeting(String message) {}

    @GetMapping("/hello")
    public Greeting hello(@RequestParam(defaultValue = "world") String name) {
        return new Greeting("Hello, " + name + "!");
    }
}
