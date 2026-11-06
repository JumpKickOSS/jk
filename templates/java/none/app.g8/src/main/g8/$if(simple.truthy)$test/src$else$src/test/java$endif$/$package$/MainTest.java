package $package$;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void greets_from_the_application() {
        assertEquals("Hello from $name$", Main.greeting());
    }
}
