package $package$

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GreeterTest:

    @Test
    def greets_by_name(): Unit =
        assertEquals("Hello, World!", Greeter("world").greet())
        assertEquals("Hello!", Greeter("").greet())
