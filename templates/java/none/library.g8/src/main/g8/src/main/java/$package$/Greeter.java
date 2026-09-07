package $package$;

import java.util.Locale;

/** A small, testable surface to start from. */
public final class Greeter {

    private final String name;

    public Greeter(String name) {
        this.name = name;
    }

    /** {@code Hello, <name>!}, with the first letter of the name in upper case. */
    public String greet() {
        if (name.isEmpty()) return "Hello!";
        return "Hello, " + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1) + "!";
    }
}
