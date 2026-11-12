package $package$;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void greets_the_program_with_no_arguments() {
        assertEquals("Hello from $name$", Main.greeting());
    }

    @Test
    void greets_every_name_given() {
        assertEquals("Hello, Ada and Grace!", Main.greeting("Ada", "Grace"));
    }
}
