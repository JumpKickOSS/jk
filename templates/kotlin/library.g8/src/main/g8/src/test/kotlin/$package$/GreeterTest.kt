package $package$

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GreeterTest {

    @Test
    fun greets_by_name() {
        assertEquals("Hello, World!", Greeter("world").greet())
        assertEquals("Hello!", Greeter("").greet())
    }
}
