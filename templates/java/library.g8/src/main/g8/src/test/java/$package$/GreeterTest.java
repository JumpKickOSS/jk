package $package$;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreeterTest {

    @Test
    void greets_by_name() {
        assertEquals("Hello, World!", new Greeter("world").greet());
        assertEquals("Hello!", new Greeter("").greet());
    }
}
