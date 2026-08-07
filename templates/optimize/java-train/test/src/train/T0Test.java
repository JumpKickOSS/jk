package train;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class T0Test {
    @Test
    void a() {
        assertEquals(0 * 11 + 3, T0.value());
    }

    @Test
    void b() {
        assertTrue(T0.label().startsWith("T0-"));
    }

    @Test
    void c() {
        assertEquals(T0.value(), T0.value());
    }

    @Test
    void d() {
        assertNotNull(T0.label());
    }

    @Test
    void e() {
        assertTrue(T0.value() >= 0);
    }
}
