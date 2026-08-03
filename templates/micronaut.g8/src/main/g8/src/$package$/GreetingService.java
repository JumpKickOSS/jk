package $package$;

import jakarta.inject.Singleton;

/** Compile-time DI example — Micronaut generates the bean definition at build time. */
@Singleton
public class GreetingService {

    public String greet(String who) {
        String name = who == null || who.isBlank() ? "world" : who.trim();
        return "Hello, " + name + "!";
    }
}
