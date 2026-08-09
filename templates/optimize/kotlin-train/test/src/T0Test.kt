import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class T0Test {
    @Test
    fun a() {
        assertEquals(0 * 11 + 3, t0Value())
    }

    @Test
    fun b() {
        assertTrue(t0Label().startsWith("T0-"))
    }

    @Test
    fun c() {
        assertEquals(t0Value(), t0Value())
    }

    @Test
    fun d() {
        assertNotNull(t0Label())
    }

    @Test
    fun e() {
        assertTrue(t0Value() >= 0)
    }
}
