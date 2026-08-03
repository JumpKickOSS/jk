package train
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
class T0Test {
    @Test fun a() { assertEquals(0 * 11 + 3, T0.value()) }
    @Test fun b() { assertTrue(T0.label().startsWith("T0-")) }
    @Test fun c() { assertEquals(T0.value(), T0.value()) }
    @Test fun d() { assertNotNull(T0.label()) }
    @Test fun e() { assertTrue(T0.value() >= 0) }
}
