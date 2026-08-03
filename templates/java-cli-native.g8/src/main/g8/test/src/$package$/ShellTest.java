package $package$;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShellTest {

    private final Shell shell = new Shell();

    @Test
    void echoes() {
        assertEquals("hi there", shell.eval("echo hi there").text());
    }

    @Test
    void exits() {
        assertTrue(shell.eval("exit").exit());
        assertTrue(shell.eval("quit").exit());
        assertFalse(shell.eval("help").exit());
    }

    @Test
    void helps_and_rejects_unknown() {
        assertEquals("commands: help, echo <text>, exit", shell.eval("help").text());
        assertTrue(shell.eval("frobnicate").text().startsWith("unknown command"));
    }
}
