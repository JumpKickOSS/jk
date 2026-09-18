// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.IdeWireModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The IntelliJ plugin's wire parser, compiled straight from {@code clients/intellij} (the
 * {@code [test] extra-src} files) so the one client-side reader of {@link IdeWireModel#encode()}
 * executes in a gate. G22 arm 4 proves textually that every field name {@code JkWireModel} reads
 * is one the encoder emits; what it cannot prove is that the parser <em>parses</em> — the
 * parallel-array alignment, the {@code moduleIndex|…} rows, the leading-noise strip, and
 * null-vs-empty on {@code error} — which is what runs here, against the real encoder rather than
 * a hand-typed JSON twin wherever the shape crosses the wire.
 */
class JkWireModelParseTest {

    @Test
    void parses_module_list_and_libs() {
        String json = """
                {"type":"ide-model-ack","error":null,"wsRoot":"/tmp/ws","rootName":"demo","workspace":true,\
                "moduleDirs":["/tmp/ws/api","/tmp/ws/app"],"names":["api","app"],\
                "javaReleases":["25","25"],"mainClasses":["","demo.Main"],\
                "libNames":["g:a:jar::1","g:b:jar::1"],"libJars":["/cache/a.jar","/cache/b.jar"],\
                "libSources":["/cache/a-sources.jar",""]}
                """;
        JkWireModel m = JkWireModel.parse(json);
        assertThat(m.error).isNull();
        assertThat(m.wsRoot).isEqualTo("/tmp/ws");
        assertThat(m.rootName).isEqualTo("demo");
        assertThat(m.workspace).isTrue();
        assertThat(m.moduleCount()).isEqualTo(2);
        assertThat(m.modules.stream().map(JkWireModel.Module::name)).containsExactly("api", "app");
        assertThat(m.modules.get(1).mainClass()).isEqualTo("demo.Main");
        assertThat(m.libs).hasSize(2);
        assertThat(m.libs.get(0).sources()).isEqualTo("/cache/a-sources.jar");
        assertThat(m.libs.get(1).sources()).isNull();
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
                List.of("/ws/api/target/test-classes", "/ws/app/target/test-classes"),
                List.of("/ws/api/target/jdt/classes/main", "/ws/app/target/jdt/classes/main"),
                List.of("/ws/api/target/jdt/classes/test", "/ws/app/target/jdt/classes/test"),
                List.of("/ws/target/api/gen/main", "/ws/target/app/gen/main"),
                List.of("/ws/target/api/gen/test", "/ws/target/app/gen/test"),
                List.of("g:a:jar::1", "g:b:jar::1"),
                List.of("g_a_jar__1", "g_b_jar__1"),
                List.of("/cache/a.jar", "/cache/b.jar"),
                List.of("/cache/a-sources.jar", ""),
                List.of("1|api|" + IdeWireModel.SCOPE_COMPILE_TEST_KIND),
                List.of("0|g:a:jar::1|MAIN", "1|g:b:jar::1|PROVIDED,PROCESSOR"),
                List.of("1|/cache/b.jar"),
                List.of("temurin-17", "temurin-25"),
                List.of("jk-temurin-17", "jk-temurin-25"),
                List.of("17", "25"),
                List.of("/jdks/17", "/jdks/25"),
                List.of("17.0.16", "25.0.3"),
                "temurin-25",
                "jk-temurin-25",
                25,
                "/jdks/25",
                "25.0.3",
                List.of("jk-temurin-25|/jdks/25|25.0.3"),
                List.of("java", "java,scala"),
                List.of("", "3.8.4"),
                List.of("1|/cache/scala3-compiler_3-3.8.4.jar"));
        JkWireModel parsed = JkWireModel.parse(model.encode());
        assertThat(parsed.error).isNull();
        assertThat(parsed.wsRoot).isEqualTo("/ws");
        assertThat(parsed.rootName).isEqualTo("demo \"quoted\"");
        assertThat(parsed.workspace).isTrue();
        // Parallel arrays stay aligned by index through encode and parse.
        assertThat(parsed.moduleDirs()).containsExactly("/ws/api", "/ws/app");
        JkWireModel.Module app = parsed.modules.get(1);
        assertThat(app.name()).isEqualTo("app");
        assertThat(app.javaRelease()).isEqualTo(25);
        assertThat(app.mainClass()).isEqualTo("demo.Main");
        assertThat(parsed.modules.get(0).mainClass()).isNull();
        assertThat(app.jdtClassesDir()).isEqualTo("/ws/app/target/jdt/classes/main");
        assertThat(app.jdtTestClassesDir()).isEqualTo("/ws/app/target/jdt/classes/test");
        assertThat(app.genSrcDir()).isEqualTo("/ws/target/app/gen/main");
        assertThat(app.sdk()).isEqualTo(new JkWireModel.Sdk("temurin-25", "jk-temurin-25", 25, "/jdks/25", "25.0.3"));
        assertThat(parsed.libs)
                .containsExactly(
                        new JkWireModel.Lib("g:a:jar::1", "g_a_jar__1", "/cache/a.jar", "/cache/a-sources.jar"),
                        new JkWireModel.Lib("g:b:jar::1", "g_b_jar__1", "/cache/b.jar", null));
        // Rows keyed by module index decode against the same index space.
        assertThat(parsed.siblingRefs)
                .containsExactly(new JkWireModel.SiblingRef(1, "api", IdeWireModel.SCOPE_COMPILE_TEST_KIND));
        assertThat(parsed.libEntries)
                .containsExactly(
                        new JkWireModel.LibEntry(0, "g:a:jar::1", List.of("MAIN")),
                        new JkWireModel.LibEntry(1, "g:b:jar::1", List.of("PROVIDED", "PROCESSOR")));
        assertThat(parsed.processorJars).containsExactly(new JkWireModel.ProcessorJar(1, "/cache/b.jar"));
        assertThat(parsed.defaultSdk)
                .isEqualTo(new JkWireModel.Sdk("temurin-25", "jk-temurin-25", 25, "/jdks/25", "25.0.3"));
        assertThat(parsed.sdkEntries).containsExactly(new JkWireModel.SdkEntry("jk-temurin-25", "/jdks/25", "25.0.3"));
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
