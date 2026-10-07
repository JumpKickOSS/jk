// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.KeyProbe;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step's key probe forked for real: what it prints last becomes a fixed-width token that moves
 * with the measurement, a failing probe fails with its output, and a probe naming a closure the
 * step does not receive is refused before anything runs.
 */
class KeyProbesTest {

    /** The probe: prints a preamble, then its arguments joined as the measurement; {@code fail} exits 3. */
    public static final class Measure {
        public static void main(String[] args) {
            System.out.println("connecting");
            if (List.of(args).contains("fail")) {
                System.out.println("no such database");
                System.exit(3);
            }
            System.out.println(String.join(" ", args));
        }
    }

    private static final Path JAVA_HOME = Path.of(System.getProperty("java.home"));

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
        assertThatThrownBy(() -> KeyProbes.token(probe, JAVA_HOME, ownClasses(), Map.of(), tmp))
                .hasMessageContaining("`jdbc-driver`")
                .hasMessageContaining("does not receive");
    }

    @Test
    void the_describe_line_carries_the_probe_and_a_step_without_one_has_none() {
        PluginDeclarations decls = PluginDeclarations.decode(
                List.of(
                        "{\"t\":\"task\",\"name\":\"generate-jooq\",\"inputs\":[\"config\"],\"outputs\":[\"generated/jooq\"],"
                                + "\"probeMain\":\"cc.jumpkick.jooq.JooqMain\",\"probeTools\":[\"jooq-codegen\"],"
                                + "\"probeArgs\":[\"--schema-digest\",\"--jdbc-url\",\"jdbc:h2:mem:x\"]}",
                        "{\"t\":\"task\",\"name\":\"generate-avro\",\"inputs\":[\"config\"],\"outputs\":[\"generated/avro\"]}"));

        assertThat(decls.steps().get(0).keyProbe())
                .isEqualTo(new KeyProbe(
                        "cc.jumpkick.jooq.JooqMain",
                        List.of("jooq-codegen"),
                        List.of("--schema-digest", "--jdbc-url", "jdbc:h2:mem:x")));
        assertThat(decls.steps().get(1).keyProbe()).isNull();
        assertThat(PlannerPlugin.cachedForecast(decls.steps().get(0)).text()).contains("probed when the step runs");
        assertThat(PlannerPlugin.cachedForecast(decls.steps().get(1)).text()).isEmpty();
    }

    private static String token(List<String> args, Path cwd) throws Exception {
        return KeyProbes.token(
                new KeyProbe(Measure.class.getName(), List.of(), args), JAVA_HOME, ownClasses(), Map.of(), cwd);
    }

    /** This module's compiled test classes, where {@link Measure} lives. */
    private static Path ownClasses() {
        return RepoRoot.dir(KeyProbesTest.class, "server/engine/target/test-classes");
    }
}
