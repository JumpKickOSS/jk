package train;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class T1Test {
    @Test
    void a() {
        assertEquals(1 * 11 + 3, T1.value());
    }

    @Test
    void b() {
        assertTrue(T1.label().startsWith("T1-"));
    }

    @Test
    void c() {
        assertEquals(T1.value(), T1.value());
    }

    @Test
    void d() {
        assertNotNull(T1.label());
    }

    @Test
    void e() {
        assertTrue(T1.value() >= 0);
    }
}
