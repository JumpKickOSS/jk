package train
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
class T1Test {
    @Test fun a() { assertEquals(1 * 11 + 3, T1.value()) }
    @Test fun b() { assertTrue(T1.label().startsWith("T1-")) }
    @Test fun c() { assertEquals(T1.value(), T1.value()) }
    @Test fun d() { assertNotNull(T1.label()) }
    @Test fun e() { assertTrue(T1.value() >= 0) }
}
