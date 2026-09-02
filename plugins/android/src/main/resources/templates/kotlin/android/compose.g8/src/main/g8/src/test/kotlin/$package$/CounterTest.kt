package $package$

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CounterTest {
    @Test
    fun increments() {
        val counter = Counter()
        assertEquals(1, counter.increment(0))
        assertEquals(2, counter.increment(1))
    }

    @Test
    fun labels() {
        val counter = Counter()
        assertEquals("Tap the button", counter.label(0))
        assertEquals("1 tap", counter.label(1))
        assertEquals("3 taps", counter.label(3))
    }
}
