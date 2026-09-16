// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A table's entries and its string-map values cross the spec wire and come back as the same nested
 * maps, so a worker reads {@code config().entries()} exactly as the engine validated them.
 */
class NestedConfigWireTest {

    @Test
    void entries_and_maps_round_trip(@TempDir Path dir) throws Exception {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("tool", "org.acme:gen:1.0");
        api.put("inputs", List.of("api/a.yaml", "api/b.yaml"));
        api.put("options", Map.of("k", "v"));
        Map<String, Object> grammar = new LinkedHashMap<>();
        grammar.put("tool", "org.antlr:antlr4:4.13.2");
        grammar.put("lite", Boolean.TRUE);
        grammar.put("depth", 3L);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flag", Boolean.FALSE);
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        entries.put("api", api);
        entries.put("grammar", grammar);
        values.put(PluginConfig.ENTRIES, entries);

        Path spec = dir.resolve("spec.jsonl");
        Files.write(
                spec,
                new SpecWriter()
                        .op("describe", null, "gen")
                        .configValues(values)
                        .lines());

        PluginConfig read = BuildPluginHarness.Spec.read(spec).config();
        assertThat(read.bool("flag")).contains(false);
        assertThat(read.entries().keySet()).containsExactly("api", "grammar");
        assertThat(read.entries().get("api"))
                .containsEntry("tool", "org.acme:gen:1.0")
                .containsEntry("inputs", List.of("api/a.yaml", "api/b.yaml"))
                .containsEntry("options", Map.of("k", "v"));
        assertThat(read.entries().get("grammar"))
                .containsEntry("lite", Boolean.TRUE)
                .containsEntry("depth", 3L);
    }
}
