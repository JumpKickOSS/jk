// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Os;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The KSP fork runs third-party processors: it gets a worker's environment, and its output whole. */
class KspProcessTest {

    private static final String SECRET = "JK_KSP_TEST_SECRET";

    @Test
    void the_ksp_fork_does_not_inherit_the_engines_environment(@TempDir Path dir) throws Exception {
        Map<String, String> engine = new LinkedHashMap<>(System.getenv());
        engine.put(SECRET, "token");
        engine.put("JAVA_TOOL_OPTIONS", "-Dfrom.the.daemon=yes");

        String out = WorkerEnv.withEngineEnvironment(
                engine, () -> PlannerKsp.runKspProcess(silent(), dir, WorkerEnv.strict(), cmd("env")));

        assertThat(names(out)).doesNotContain(SECRET, "JAVA_TOOL_OPTIONS").contains("PATH");
    }

    /** A variable this process really has that a worker is not given: the fork must not see it. */
    @Test
    void the_ksp_fork_does_not_see_this_processs_own_variables(@TempDir Path dir) throws Exception {
        Optional<String> outsider = System.getenv().keySet().stream()
                .filter(k -> !WorkerEnv.allowed(k, Os.isWindows()))
                .filter(k -> !k.equalsIgnoreCase("JAVA_TOOL_OPTIONS"))
                // Every worker is given JAVA_HOME: the JDK it was launched under.
                .filter(k -> !k.equalsIgnoreCase("JAVA_HOME"))
                .sorted()
                .findFirst();
        Assumptions.assumeTrue(outsider.isPresent(), "no variable outside the worker allow-list to plant");

        String out = PlannerKsp.runKspProcess(silent(), dir, WorkerEnv.strict(), cmd("env"));

        assertThat(names(out)).doesNotContain(nameKey(outsider.get()));
    }

    /** One byte, then two-byte characters: every even-sized read boundary falls inside one. */
    @Test
    void a_character_split_across_reads_is_decoded_whole(@TempDir Path dir) throws Exception {
        String out = PlannerKsp.runKspProcess(silent(), dir, WorkerEnv.strict(), cmd("split"));
        assertThat(out).isEqualTo(SPLIT);
    }

    private static final String SPLIT = "x" + "é".repeat(20_000) + "\n";

    /** The variable names a {@code name=value} listing holds. */
    /** The variable names in an {@code env} listing, upper-cased on Windows where case does not matter. */
    private static List<String> names(String listing) {
        return listing.lines()
                .map(l -> l.substring(0, Math.max(0, l.indexOf('='))))
                .map(KspProcessTest::nameKey)
                .toList();
    }

    private static String nameKey(String name) {
        return Os.isWindows() ? name.toUpperCase(Locale.ROOT) : name;
    }

    private static List<String> cmd(String... args) {
        List<String> cmd = new ArrayList<>(List.of(
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                FakeKsp.class.getName()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    /** Stands in for KSP: lists its environment, or prints {@link #SPLIT}. */
    public static final class FakeKsp {
        public static void main(String[] args) {
            PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            if (args[0].equals("env")) {
                System.getenv().forEach((name, value) -> out.println(name + "=" + value));
            } else {
                // One write larger than any read, so the reads, not the writes, decide the boundaries.
                byte[] bytes = SPLIT.getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
            out.flush();
        }
    }

    private static TaskContext silent() {
        return new TaskContext() {
            @Override
            public void progress(int delta) {}

            @Override
            public void updateTicks(int additional) {}

            @Override
            public void label(@Nullable String description) {}

            @Override
            public void output(@Nullable String line) {}

            @Override
            public void warn(String code, String message) {}

            @Override
            public void error(String code, String message) {}

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public <T> void put(BuildPlanKey<T> key, T value) {}

            @Override
            public <T> Optional<T> get(BuildPlanKey<T> key) {
                return Optional.empty();
            }

            @Override
            public <T> T require(BuildPlanKey<T> key) {
                throw new IllegalStateException(key.toString());
            }
        };
    }
}
