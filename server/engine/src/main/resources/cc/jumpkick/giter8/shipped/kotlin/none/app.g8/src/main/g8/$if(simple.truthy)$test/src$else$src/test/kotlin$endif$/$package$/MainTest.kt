package $package$

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun greets_from_the_application() {
        assertEquals("Hello from $name$", greeting())
    }
}
