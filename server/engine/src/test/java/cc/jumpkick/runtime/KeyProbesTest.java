// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.KeyProbe;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step's key probe forked for real from a plugin jar and the POM beside it: what it prints last
 * becomes a fixed-width token that moves with the measurement, a failing probe fails with its
 * output, a probe naming a closure the step does not receive is refused before anything runs, and
 * the probe runs on the libraries the plugin's POM names, as every fork of the worker does.
 */
class KeyProbesTest {

    /**
     * The probe: prints a preamble, then its arguments joined as the measurement; {@code fail} exits 3,
     * {@code env} measures {@code PROBE_SECRET} and {@code HOME} from its environment instead, and
     * {@code library} measures what {@link Library}, in a jar of its own, answers.
     */
    public static final class Measure {
        public static void main(String[] args) {
            System.out.println("connecting");
            if (List.of(args).contains("env")) {
                System.out.println(System.getenv("PROBE_SECRET") + " " + System.getenv("HOME"));
                return;
            }
            if (List.of(args).contains("library")) {
                System.out.println(Library.answer());
                return;
            }
            if (List.of(args).contains("fail")) {
                System.out.println("no such database");
                System.exit(3);
            }
            System.out.println(String.join(" ", args));
        }
    }

    /** What the plugin's POM names: a library the plugin jar does not carry. */
    public static final class Library {
        static String answer() {
            return "from the library";
        }
    }

    private static final Path JAVA_HOME = Path.of(System.getProperty("java.home"));

    @Test
    void the_probe_runs_on_the_libraries_the_plugin_s_pom_names(@TempDir Path tmp) throws Exception {
        assertThat(token(List.of("library"), tmp)).isEqualTo(token(List.of("from", "the", "library"), tmp));
    }

    @Test
    void the_last_line_keys_the_step_and_moves_with_the_measurement(@TempDir Path tmp) throws Exception {
        String first = token(List.of("schema", "v1"), tmp);
        assertThat(first).matches("probe:[0-9a-f]{64}");
        assertThat(token(List.of("schema", "v1"), tmp)).isEqualTo(first);
        assertThat(token(List.of("schema", "v2"), tmp)).isNotEqualTo(first);
    }

    @Test
    void a_failing_probe_fails_with_its_output(@TempDir Path tmp) {
        assertThatThrownBy(() -> token(List.of("fail"), tmp))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(Measure.class.getName() + " failed (exit 3)")
                .hasMessageContaining("no such database");
    }

    @Test
    void a_closure_the_step_does_not_receive_is_refused(@TempDir Path tmp) {
        KeyProbe probe = new KeyProbe(Measure.class.getName(), List.of("jdbc-driver"), List.of());
        assertThatThrownBy(() -> KeyProbes.token(probe, JAVA_HOME, pluginJar(tmp), Map.of(), tmp, Map.of(), Map.of()))
                .hasMessageContaining("`jdbc-driver`")
                .hasMessageContaining("does not receive");
    }

    /**
     * A probe's secret reaches it through its environment, which carries nothing the engine's own
     * shell does, as the plugin's secrets answer the key it names; one the module does not set
     * leaves the variable unset.
     */
    @Test
    void the_probe_reads_its_secret_from_an_environment_it_does_not_inherit(@TempDir Path tmp) throws Exception {
        KeyProbe probe =
                new KeyProbe(Measure.class.getName(), List.of(), List.of("env"), Map.of("PROBE_SECRET", "db-password"));

        assertThat(KeyProbes.token(
                        probe,
                        JAVA_HOME,
                        pluginJar(tmp),
                        Map.of(),
                        tmp,
                        Map.of("LANG", "C"),
                        Map.of("db-password", "s3cret")))
                .isEqualTo(token(List.of("s3cret", "null"), tmp));
        assertThat(KeyProbes.token(probe, JAVA_HOME, pluginJar(tmp), Map.of(), tmp, Map.of("LANG", "C"), Map.of()))
                .isEqualTo(token(List.of("null", "null"), tmp));
    }

    @Test
    void the_describe_line_carries_the_probe_and_a_step_without_one_has_none() {
        PluginDeclarations decls = PluginDeclarations.decode(
                List.of(
                        "{\"t\":\"task\",\"name\":\"generate-jooq\",\"inputs\":[\"config\"],\"outputs\":[\"generated/jooq\"],"
                                + "\"probeMain\":\"cc.jumpkick.jooq.JooqMain\",\"probeTools\":[\"jooq-codegen\"],"
                                + "\"probeArgs\":[\"--schema-digest\",\"--jdbc-url\",\"jdbc:h2:mem:x\"],"
                                + "\"probeSecretEnv\":{\"JOOQ_JDBC_PASSWORD\":\"jdbc-password\"}}",
                        "{\"t\":\"task\",\"name\":\"generate-avro\",\"inputs\":[\"config\"],\"outputs\":[\"generated/avro\"]}"));

        assertThat(decls.steps().get(0).keyProbe())
                .isEqualTo(new KeyProbe(
                        "cc.jumpkick.jooq.JooqMain",
                        List.of("jooq-codegen"),
                        List.of("--schema-digest", "--jdbc-url", "jdbc:h2:mem:x"),
                        Map.of("JOOQ_JDBC_PASSWORD", "jdbc-password")));
        assertThat(decls.steps().get(1).keyProbe()).isNull();
        assertThat(PlannerPlugin.cachedForecast(decls.steps().get(0)).text()).contains("probed when the step runs");
        assertThat(PlannerPlugin.cachedForecast(decls.steps().get(1)).text()).isEmpty();
    }

    private static String token(List<String> args, Path cwd) throws Exception {
        return KeyProbes.token(
                new KeyProbe(Measure.class.getName(), List.of(), args),
                JAVA_HOME,
                pluginJar(cwd),
                Map.of(),
                cwd,
                Map.of(),
                Map.of());
    }

    /**
     * A plugin shelved in a store under {@code tmp}: its jar holds {@link Measure} only, and its POM
     * names the library jar that holds {@link Library}.
     */
    private static Path pluginJar(Path tmp) throws IOException {
        Path shelf = tmp.resolve("store/repos/jk-local/com/acme");
        Path plugin = shelf.resolve("probe/1.0/probe-1.0.jar");
        if (Files.isRegularFile(plugin)) return plugin;
        jar(plugin, Measure.class);
        Files.writeString(shelf.resolve("probe/1.0/probe-1.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>probe</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>library</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        jar(shelf.resolve("library/1.0/library-1.0.jar"), Library.class);
        Files.writeString(shelf.resolve("library/1.0/library-1.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>library</artifactId><version>1.0</version>
                </project>
                """);
        return plugin;
    }

    /** A jar of {@code type}'s compiled class, taken from this module's test classes. */
    private static void jar(Path jar, Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(Files.readAllBytes(ownClasses().resolve(entry)));
            out.closeEntry();
        }
    }

    /** This module's compiled test classes, where {@link Measure} and {@link Library} live. */
    private static Path ownClasses() {
        return RepoRoot.dir(KeyProbesTest.class, "server/engine/target/test-classes");
    }
}
