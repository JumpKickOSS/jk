// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.TestStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A declared suite's {@code explicit} flag decides whether {@code jk test -A} runs it, so flipping it
 * must move the run-tests stamp: otherwise a green marker recorded while the suite was left out
 * replays over a run that would now execute it.
 */
class SuiteExplicitStampTest {

    private static final String MANIFEST = """
            name = "demo"
            group = "t"
            version = "0.0.1"
            java = 25

            [test.suites.live]
            tags = ["live"]
            %s
            """;

    @Test
    void flipping_explicit_changes_what_all_runs_and_must_change_the_stamp(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/test/java/app/LiveTest.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package app;\nclass LiveTest {}\n");
        Path lock = Files.writeString(dir.resolve("jk-lock.toml"), "");
        TestSelection all = TestSelection.of(List.of(), true, List.of(), List.of());

        Run explicit = run(dir, "explicit = true", all, src, lock);
        Run plain = run(dir, "explicit = false", all, src, lock);

        assertThat(explicit.suites())
                .as("-A leaves the explicit suite out; the built-in integration suite stays")
                .containsExactly(TestSuites.DEFAULT, TestSuites.INTEGRATION);
        assertThat(plain.suites())
                .as("-A runs it once it is not explicit")
                .containsExactly(TestSuites.DEFAULT, "live", TestSuites.INTEGRATION);
        assertThat(plain.compiled()).as("both runs compile the same roots").isEqualTo(explicit.compiled());
        assertThat(plain.key())
                .as("a different set of suites runs, so the run-tests stamp must differ")
                .isNotEqualTo(explicit.key());
    }

    private record Run(List<String> suites, List<String> compiled, String key) {}

    private static Run run(Path dir, String explicitLine, TestSelection sel, Path src, Path lock) throws Exception {
        Path manifest = Files.writeString(dir.resolve("jk.toml"), MANIFEST.formatted(explicitLine));
        JkBuild project = JkBuildParser.parseLocal(manifest);
        var resolved =
                TestStampInputs.resolveSuites(sel, dir, false, project.build().runSuites());
        List<String> compiled = TestSuites.compiled(
                dir, false, resolved.suites(), project.build().runSuites());
        BuildLayout layout = BuildLayout.of(dir, project);
        Files.createDirectories(layout.classesDir());
        TestStampInputs stamp = TestStampInputs.of(
                new TestStampInputs.Facts(
                        dir,
                        project,
                        layout,
                        null,
                        sel,
                        null,
                        Map.of(),
                        List.of(),
                        List.of(),
                        TestStamp.CompileTestKeys.NONE),
                ClasspathFingerprint.ON_DISK);
        String key = stamp.key(List.of(src), layout.classesDir(), null, List.of(), lock, stamp.extras());
        assertThat(key).as("every input readable").isNotNull();
        return new Run(resolved.suites(), compiled, Objects.requireNonNull(key));
    }
}
