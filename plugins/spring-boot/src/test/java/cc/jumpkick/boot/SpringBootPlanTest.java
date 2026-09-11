// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the Spring Boot plugin declares to the engine — the optional {@code spring-aot} step and
 * the {@code boot-jar} packager — asserted on the describe protocol, which is what the engine
 * fingerprints and wires into the plan.
 */
class SpringBootPlanTest {

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new SpringBootPlugin().manifest();
        assertThat(manifest.id()).isEqualTo("jk-spring-boot");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKSB:");
    }

    /** With no `aot` key and no `[native]`, the plan is the packager alone. */
    @Test
    void a_plain_project_gets_the_boot_jar_packager_and_no_aot_step(@TempDir Path dir) throws Exception {
        List<String> lines = describe(dir, null, false);
        assertThat(aotTask(lines)).isEmpty();
        String packager = lines.stream()
                .filter(l -> l.contains("\"t\":\"packager\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no packager line"));
        assertThat(packager).contains("\"name\":\"boot-jar\"");
        // The AOT step output is declared even when the step is not: its absence is its own
        // fingerprint, so turning AOT on later re-packages.
        assertThat(arrayOf(packager, "inputs"))
                .containsExactly("classes", "runtime-entries", "step:spring-aot", "config");
        assertThat(lines).noneMatch(l -> l.contains("\"t\":\"command\""));
    }

    /** {@code aot = true} adds the step: classes + runtime classpath + config in, three dirs out, two contributed. */
    @Test
    void aot_on_declares_the_spring_aot_step(@TempDir Path dir) throws Exception {
        String task = aotTask(describe(dir, true, false)).orElseThrow(() -> new AssertionError("no spring-aot task"));
        assertThat(arrayOf(task, "requires")).isEmpty();
        assertThat(arrayOf(task, "inputs")).containsExactly("classes", "runtime-classpath", "config");
        assertThat(arrayOf(task, "outputs")).containsExactly("classes", "resources", "sources");
        assertThat(arrayOf(task, "contributesClasses")).containsExactly("classes");
        assertThat(arrayOf(task, "contributesResources")).containsExactly("resources");
        // Generated sources are compiled by the step itself into `classes`; they are not handed
        // to the module's compiler.
        assertThat(arrayOf(task, "contributesSources")).isEmpty();
        assertThat(arrayOf(task, "contributesTestClasspath")).isEmpty();
        assertThat(task).contains("\"transformsClasses\":\"\"");
        assertThat(task).contains("\"stage\":\"\"");
    }

    /** The auto-gate: a declared `[native]` turns AOT on by itself when the key is absent. */
    @Test
    void a_declared_native_build_turns_aot_on_with_no_aot_key(@TempDir Path dir) throws Exception {
        assertThat(aotTask(describe(dir, null, true))).isPresent();
        assertThat(aotTask(describe(dir, null, false))).isEmpty();
    }

    /** An explicit `aot = false` outranks `[native]`: the key always wins. */
    @Test
    void an_explicit_false_suppresses_aot_even_for_a_native_build(@TempDir Path dir) throws Exception {
        assertThat(aotTask(describe(dir, false, true))).isEmpty();
    }

    private static Optional<String> aotTask(List<String> lines) {
        return lines.stream()
                .filter(l -> l.contains("\"t\":\"task\"") && l.contains("\"name\":\"spring-aot\""))
                .findFirst();
    }

    /** Run the plugin's describe op; `aot` null means the key is absent. */
    private static List<String> describe(Path dir, @Nullable Boolean aot, boolean nativeDeclared) throws Exception {
        Path spec = dir.resolve("describe.spec");
        List<String> lines = new ArrayList<>(List.of(
                "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-spring-boot\"}",
                "{\"t\":\"config\",\"key\":\"version\",\"kind\":\"string\",\"value\":\"latest\"}",
                "{\"t\":\"project\",\"group\":\"com.example\",\"name\":\"app\",\"version\":\"1\","
                        + "\"javaRelease\":25,\"nativeDeclared\":" + nativeDeclared + ",\"kotlin\":false}"));
        if (aot != null) {
            lines.add(2, "{\"t\":\"config\",\"key\":\"aot\",\"kind\":\"bool\",\"value\":" + aot + "}");
        }
        Files.write(spec, lines);
        var buffer = new ByteArrayOutputStream();
        var writer = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKSB:");
        int exit = new SpringBootPlugin().run(List.of(spec.toString()), writer);
        assertThat(exit).isZero();
        return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
    }

    /** A JSON string array field of a describe line, as its element strings. */
    private static List<String> arrayOf(String line, String field) {
        String key = "\"" + field + "\":[";
        int at = line.indexOf(key);
        assertThat(at).as(field + " in " + line).isNotNegative();
        int open = at + key.length();
        String body = line.substring(open, line.indexOf(']', open));
        return List.of(body.split(",")).stream()
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
