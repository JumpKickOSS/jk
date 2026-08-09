import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class T1Test {
    @Test
    fun a() {
        assertEquals(1 * 11 + 3, t1Value())
    }

    @Test
    fun b() {
        assertTrue(t1Label().startsWith("T1-"))
    }

    @Test
    fun c() {
        assertEquals(t1Value(), t1Value())
    }

    @Test
    fun d() {
        assertNotNull(t1Label())
    }

    @Test
    fun e() {
        assertTrue(t1Value() >= 0)
    }
}
