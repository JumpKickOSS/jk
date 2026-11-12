package $package$;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.BuildPluginContext;
import cc.jumpkick.plugin.build.PackagerSpec;
import cc.jumpkick.plugin.build.PluginCommandSpec;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.TaskSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class $name;format="Camel"$PluginTest {

    private final $name;format="Camel"$Plugin plugin = new $name;format="Camel"$Plugin();

    @Test
    void the_class_speaks_with_the_id_and_prefix_the_manifest_declares() throws IOException {
        String manifest;
        try (InputStream in = getClass().getResourceAsStream("/jk-plugin.toml")) {
            assertNotNull(in, "jk-plugin.toml is on the classpath, as it is at the jar root");
            manifest = new String(in.readAllBytes(), UTF_8);
        }
        assertTrue(manifest.contains("id        = \"" + plugin.manifest().id() + "\""), manifest);
        assertTrue(manifest.contains("protocol-prefix = \"" + plugin.manifest().protocolPrefix() + "\""), manifest);
    }

    @Test
    void registers_a_command_that_prints_the_greeting() {
        List<PluginCommandSpec> commands = new ArrayList<>();
        plugin.register(new BuildPluginContext() {
            @Override
            public PluginConfig config() {
                throw new UnsupportedOperationException("registration reads no config");
            }

            @Override
            public ProjectFacts project() {
                throw new UnsupportedOperationException("registration reads no project facts");
            }

            @Override
            public void task(TaskSpec spec) {}

            @Override
            public void packaging(PackagerSpec spec) {}

            @Override
            public void command(PluginCommandSpec spec) {
                commands.add(spec);
            }
        });

        assertEquals(List.of("$name$"), commands.stream().map(PluginCommandSpec::name).toList());
        PluginConfig config = new PluginConfig("$name$", Map.of("greeting", "hi"));
        assertEquals("hi from the $name$ plugin", $name;format="Camel"$Plugin.greeting(config));
    }
}
