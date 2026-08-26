// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.IdeWireModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The IntelliJ plugin's regex wire parser, compiled straight from {@code clients/intellij} (the
 * {@code intellijParserSrc} source dir) so the one client-side reader of
 * {@link IdeWireModel#encode()} executes in a gate. G22 arm 4 proves textually that every field
 * name {@code JkWireModel} reads is one the encoder emits; what it cannot prove is that the parser
 * <em>parses</em> — the parallel-array alignment, the leading-noise strip, and null-vs-empty on
 * {@code error} — which is what runs here, against the real encoder rather than a hand-typed JSON
 * twin wherever the shape crosses the wire.
 */
class JkWireModelParseTest {

    @Test
    void parses_module_list_and_libs() {
        String json = """
                {"type":"ide-model-ack","error":null,"wsRoot":"/tmp/ws","rootName":"demo","workspace":true,\
                "moduleDirs":["/tmp/ws/api","/tmp/ws/app"],"names":["api","app"],\
                "javaReleases":["25","25"],"mainClasses":["","demo.Main"],\
                "libJars":["/cache/a.jar","/cache/b.jar"],"libSources":["/cache/a-sources.jar",""]}
                """;
        JkWireModel m = JkWireModel.parse(json);
        assertThat(m.error).isNull();
        assertThat(m.wsRoot).isEqualTo("/tmp/ws");
        assertThat(m.rootName).isEqualTo("demo");
        assertThat(m.workspace).isTrue();
        assertThat(m.moduleCount()).isEqualTo(2);
        assertThat(m.names).containsExactly("api", "app");
        assertThat(m.libJars).hasSize(2);
        assertThat(m.libSources.get(0)).isEqualTo("/cache/a-sources.jar");
    }

    @Test
    void strips_leading_noise_before_json() {
        String raw = "some wedge line\n{\"type\":\"ide-model-ack\",\"error\":null,\"wsRoot\":\"/p\",\"rootName\":\"x\","
                + "\"workspace\":false,\"moduleDirs\":[\"/p\"],\"names\":[\"x\"],"
                + "\"javaReleases\":[\"25\"],\"mainClasses\":[\"\"],"
                + "\"libJars\":[],\"libSources\":[]}\n";
        JkWireModel m = JkWireModel.parse(raw);
        assertThat(m.wsRoot).isEqualTo("/p");
        assertThat(m.moduleCount()).isEqualTo(1);
    }

    @Test
    void parses_what_the_real_encoder_emits() {
        IdeWireModel model = new IdeWireModel(
                null,
                "/ws",
                "demo \"quoted\"",
                true,
                List.of("/ws/api", "/ws/app"),
                List.of("api", "app"),
                List.of("17", "25"),
                List.of("", "demo.Main"),
                List.of("/ws/api/target/classes", "/ws/app/target/classes"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("a", "b"),
                List.of("/cache/a.jar", "/cache/b.jar"),
                List.of("/cache/a.jar", "/cache/b.jar"),
                List.of("/cache/a-sources.jar", ""),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                0,
                "",
                "",
                List.of());
        JkWireModel parsed = JkWireModel.parse(model.encode());
        assertThat(parsed.error).isNull();
        assertThat(parsed.wsRoot).isEqualTo("/ws");
        assertThat(parsed.rootName).isEqualTo("demo \"quoted\"");
        assertThat(parsed.workspace).isTrue();
        // Parallel arrays stay aligned by index through encode and parse.
        assertThat(parsed.moduleDirs).containsExactly("/ws/api", "/ws/app");
        assertThat(parsed.names).containsExactly("api", "app");
        assertThat(parsed.javaReleases).containsExactly("17", "25");
        assertThat(parsed.mainClasses).containsExactly("", "demo.Main");
        assertThat(parsed.libJars).containsExactly("/cache/a.jar", "/cache/b.jar");
        assertThat(parsed.libSources).containsExactly("/cache/a-sources.jar", "");
    }

    @Test
    void error_model_round_trips_as_non_null_error() {
        JkWireModel parsed =
                JkWireModel.parse(IdeWireModel.error("no jk.toml found").encode());
        assertThat(parsed.error).isEqualTo("no jk.toml found");
        assertThat(parsed.moduleCount()).isZero();
        assertThat(parsed.workspace).isFalse();
    }
}
