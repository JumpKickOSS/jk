package $package$

import java.util.Locale

/** A small, testable surface to start from. */
final class Greeter(name: String):

    /** `Hello, <name>!`, with the first letter of the name in upper case. */
    def greet(): String =
        if name.isEmpty then "Hello!"
        else "Hello, " + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1) + "!"
