// SPDX-License-Identifier: Apache-2.0
package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class CliTest {

    @Test
    void discoversOneCommandFromEachByNameConvention() {
        assertEquals(List.of("demo.GreetCommand"), Cli.registeredClassNames());
        assertEquals(List.of("greet", "title"), List.copyOf(Cli.discover().keySet()));
    }

    @Test
    void runsTheVerbsTheReadmeClaims() {
        SortedMap<String, Command> commands = Cli.discover();
        assertEquals("hello, Ada", commands.get("greet").run(List.of("Ada")));
        assertEquals("Ada Lovelace", commands.get("title").run(List.of("ADA", "lovelace")));
    }

    @Test
    void aRegistryEntryTheJarLostFailsWithTheResourceInTheMessage() {
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> Cli.instantiate("demo.GreetCommandXxx"));
        assertEquals(
                "demo/commands.properties names demo.GreetCommandXxx," + " which this jar does not carry",
                thrown.getMessage());
        assertInstanceOf(ClassNotFoundException.class, thrown.getCause());
    }
}
