// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.SuiteSettings;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.SuiteFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A launch whose every class passed earlier, is nested in one that did, or holds no test starts no
 * JVM: its JVMs would discover nothing. The launch is still entered, so the classes it leaves out
 * count as replayed. A class left to run, or a launch that leaves nothing out, starts as before.
 */
class SettledLaunchTest {

    @Test
    void a_launch_whose_classes_all_passed_earlier_starts_no_jvm(@TempDir Path m) throws Exception {
        compiled(m, "ATest", "BTest", "ATest$Inner");

        Run run = run(m, List.of("demo.ATest", "demo.BTest"), name -> true);

        assertThat(run.entered)
                .as("the launch is entered, so its settled classes count")
                .containsExactly("");
        assertThat(run.started).as("no JVM started").isEmpty();
        assertThat(run.summary.total()).isZero();
    }

    @Test
    void a_class_left_to_run_starts_the_launch(@TempDir Path m) throws Exception {
        compiled(m, "ATest", "BTest");

        assertThat(run(m, List.of("demo.ATest"), name -> true).started).containsExactly("");
    }

    @Test
    void a_class_that_holds_no_test_is_not_left_to_run(@TempDir Path m) throws Exception {
        compiled(m, "ATest", "Support", "Support$1");

        assertThat(run(m, List.of("demo.ATest"), name -> !name.equals("demo.Support")).started)
                .isEmpty();
    }

    @Test
    void a_launch_that_leaves_nothing_out_starts_as_before(@TempDir Path m) throws Exception {
        compiled(m, "Support");

        assertThat(run(m, List.of(), name -> false).started)
                .as("with nothing settled the runner judges, as it always has")
                .containsExactly("");
    }

    @Test
    void each_launch_of_a_split_run_is_judged_by_its_own_settled_classes(@TempDir Path m) throws Exception {
        compiled(m, "ATest", "BTest");
        Map<String, DeclaredSuite> declared = new LinkedHashMap<>();
        declared.put(
                "integration",
                new DeclaredSuite(
                        List.of(),
                        List.of("integration"),
                        List.of(),
                        List.of(),
                        new SuiteSettings(Map.of("A", "1"), List.of(), Map.of(), null),
                        false));
        List<String> suites = List.of(TestSuites.DEFAULT, "integration");
        Run run = new Run();
        SuiteResources.of(m, false, TestSuites.compiled(m, false, suites, declared), List.of())
                .launch(
                        m.resolve("target/test-classes"),
                        SuiteFilter.of(m, false, suites, declared),
                        List.of(),
                        List.of(),
                        null,
                        (name, settings) -> {
                            run.entered.add(name);
                            return new JUnitLauncher()
                                    .withSettled(
                                            name.equals("test:test") ? List.of("demo.ATest", "demo.BTest") : List.of());
                        },
                        name -> true,
                        List.of(),
                        (one, cp, launch) -> {
                            run.started.add(String.join(",", launch.suites()));
                            return new TestSummary(1, 1, 0, 0, List.of());
                        });

        assertThat(run.entered).contains("test:test", "test:integration");
        assertThat(run.started).as("only the launch with nothing settled").containsExactly("integration");
    }

    /** What one run of the default suite did: the launches it entered and those whose JVMs it started. */
    private static final class Run {
        final List<String> entered = new ArrayList<>();
        final List<String> started = new ArrayList<>();
        TestSummary summary = new TestSummary(0, 0, 0, 0, List.of());
    }

    private static Run run(Path m, List<String> settled, Predicate<String> holdsTests) throws Exception {
        List<String> suites = List.of(TestSuites.DEFAULT);
        Run run = new Run();
        run.summary = SuiteResources.NONE.launch(
                m.resolve("target/test-classes"),
                SuiteFilter.of(m, false, suites, Map.of()),
                List.of(),
                List.of(),
                null,
                (name, settings) -> {
                    run.entered.add(name);
                    return new JUnitLauncher().withSettled(settled);
                },
                holdsTests,
                List.of(),
                (one, cp, launch) -> {
                    run.started.add("");
                    return new TestSummary(3, 3, 0, 0, List.of());
                });
        return run;
    }

    /** {@code classes} as sources of the default suite in package demo, and as compiled class files. */
    private static void compiled(Path m, String... classes) throws Exception {
        Path src = Files.createDirectories(m.resolve("src/test/java/demo"));
        Path out = Files.createDirectories(m.resolve("target/test-classes/demo"));
        for (String cls : classes) {
            if (!cls.contains("$"))
                Files.writeString(src.resolve(cls + ".java"), "package demo; class " + cls + " {}\n");
            Files.write(out.resolve(cls + ".class"), new byte[0]);
        }
    }
}
