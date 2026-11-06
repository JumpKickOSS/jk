package $package$

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainTest:

    @Test
    def greets_from_the_application(): Unit = assertEquals("Hello from $name$", Main.greeting)
