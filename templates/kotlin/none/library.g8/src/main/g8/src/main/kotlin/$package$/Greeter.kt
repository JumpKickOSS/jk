package $package$

import java.util.Locale

/** A small, testable surface to start from. */
class Greeter(private val name: String) {

    /** `Hello, <name>!`, with the first letter of the name in upper case. */
    fun greet(): String {
        if (name.isEmpty()) return "Hello!"
        return "Hello, " + name.replaceFirstChar { it.titlecase(Locale.ROOT) } + "!"
    }
}
