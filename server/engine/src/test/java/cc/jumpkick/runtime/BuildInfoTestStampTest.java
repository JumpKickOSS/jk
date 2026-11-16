// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.layout.BuildLayout;
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
 * A module with {@code [build-info]} keeps {@code git.properties} (and Boot's {@code
 * META-INF/build-info.properties}) in its classes tree, which the run-tests stamp hashes. Keyed by
 * their bytes, the first edit anywhere in the checkout after a clean tree ({@code git.dirty}) and
 * every commit re-ran the module's whole suite, Testcontainers tests and all, for a change no test
 * observes: hotel-reservations' rest ran 28.8 s for an assertion edited in core (JK-M9055). The
 * stamp keys those files by their stable entries, unless the suite's sources name a volatile one.
 */
class BuildInfoTestStampTest {

    private static final String MANIFEST = """
            name = "app"
            group = "t"
            version = "1.0.0"
            java = 25

            [build-info]
            """;

    @Test
    void the_dirty_flip_and_a_new_commit_replay_the_suite(@TempDir Path dir) throws Exception {
        Path src = source(dir, "class AppTest { void t() { assert 1 == 1; } }\n");
        String clean = key(dir, src, info("1.0.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false));
        assertThat(key(dir, src, info("1.0.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", true)))
                .as("the first edit after a clean tree")
                .isEqualTo(clean);
        assertThat(key(dir, src, info("1.0.0", "0123456789abcdef0123456789abcdef01234567", false)))
                .as("a new commit")
                .isEqualTo(clean);
    }

    /** No under-run: a value a test can assert on and that moves only with the project still re-runs it. */
    @Test
    void a_stable_value_still_re_runs_the_suite(@TempDir Path dir) throws Exception {
        Path src = source(dir, "class AppTest { void t() { assert 1 == 1; } }\n");
        assertThat(key(dir, src, info("1.0.1", "8e647d84db6becc16dadd8729a78cf525bc3a925", false)))
                .isNotEqualTo(key(dir, src, info("1.0.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false)));
    }

    /** No under-run: a suite that reads the commit keys it. */
    @Test
    void a_suite_that_names_a_volatile_value_re_runs_when_it_moves(@TempDir Path dir) throws Exception {
        Path src = source(
                dir,
                "class AppTest { void t() throws Exception { var p = new java.util.Properties();"
                        + " p.load(getClass().getResourceAsStream(\"/git.properties\"));"
                        + " assert p.getProperty(\"git.commit.id\") != null; } }\n");
        String clean = key(dir, src, info("1.0.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false));
        assertThat(key(dir, src, info("1.0.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", true)))
                .isNotEqualTo(clean);
        assertThat(key(dir, src, info("1.0.0", "0123456789abcdef0123456789abcdef01234567", false)))
                .isNotEqualTo(clean);
    }

    /** Any other change to the classes tree re-runs the suite as before. */
    @Test
    void the_rest_of_the_tree_still_keys(@TempDir Path dir) throws Exception {
        Path src = source(dir, "class AppTest { void t() { assert 1 == 1; } }\n");
        String info = info("1.0.0", "8e647d84db6becc16dadd8729a78cf525bc3a925", false);
        String before = key(dir, src, info);
        Path classes = BuildLayout.of(dir, JkBuildParser.parseLocal(dir.resolve("jk.toml")))
                .classesDir();
        Files.writeString(classes.resolve("application.yaml"), "a: 1\n");
        assertThat(key(dir, src, info)).isNotEqualTo(before);
    }

    private static String info(String version, String sha, boolean dirty) {
        return """
                git.branch=main
                git.build.time=2026-11-15T22\\:54\\:41+0000
                git.build.version=%s
                git.commit.id=%s
                git.commit.id.abbrev=%s
                git.commit.time=2026-11-15T22\\:54\\:41+0000
                git.dirty=%s
                """.formatted(version, sha, sha.substring(0, 7), dirty);
    }

    private static Path source(Path dir, String text) throws Exception {
        Path src = dir.resolve("src/test/java/app/AppTest.java");
        Files.createDirectories(src.getParent());
        return Files.writeString(src, "package app;\n" + text);
    }

    private static String key(Path dir, Path src, String gitProperties) throws Exception {
        Path manifest = Files.writeString(dir.resolve("jk.toml"), MANIFEST);
        Path lock = Files.writeString(dir.resolve("jk-lock.toml"), "");
        JkBuild project = JkBuildParser.parseLocal(manifest);
        BuildLayout layout = BuildLayout.of(dir, project);
        Files.createDirectories(layout.classesDir().resolve("app"));
        Files.writeString(layout.classesDir().resolve("app/App.class"), "bytes");
        Files.writeString(layout.classesDir().resolve("git.properties"), gitProperties);
        TestStampInputs stamp = TestStampInputs.of(
                new TestStampInputs.Facts(
                        dir,
                        project,
                        layout,
                        JavaHomes.runningJavaHome(),
                        null,
                        TestSelection.DEFAULT,
                        null,
                        Map.of(),
                        List.of(),
                        List.of(),
                        TestStamp.CompileTestKeys.NONE),
                ClasspathFingerprint.ON_DISK);
        String key = stamp.key(List.of(src), layout.classesDir(), null, List.of(), lock, stamp.extras());
        return Objects.requireNonNull(key, "every input readable");
    }
}
