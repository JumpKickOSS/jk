// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * One JUnit version across every fixture manifest, and it is the one jk itself resolved.
 *
 * <p>Test fixtures that scaffold a project pin the JUnit train exactly — {@code version = "6.1.3"}
 * rather than {@code latest} — and that pin is deliberate: {@code KotlinSerializationTest} records
 * why, and a dozen fixtures point at it. Owning {@code [test-dependencies]} keeps the injected
 * {@code junit-jupiter} {@code latest} out of the graph, so the lock a test writes is deterministic;
 * a half-published release (metadata advertising a version whose POM still 404s, seen live with
 * 6.1.2) cannot then decide what a fixture resolves.
 *
 * <p>What the pins must not do is <em>drift apart</em>. They sat at 6.1.1 while jk's own lock had
 * moved to 6.1.3, so the tree was testing against a JUnit nobody shipped against, and a reader had
 * no way to tell which number was current. This is the guard for that: every pin equals the version
 * {@code jk-lock.toml} resolved, so a deliberate {@code jk lock} bump carries the fixtures with it
 * and a hand-edited pin fails here instead of quietly diverging.
 *
 * <p>It reads {@code jk-lock.toml}: that is the one record of what jk resolved.
 *
 * <p>Not in scope: coordinate strings used as arbitrary fixture data — mock-repo seeds
 * ({@code serveLeaf}, {@code seedArtifact}), parser and editor tests, solver fixtures. Those never
 * reach a repository, so their version is a label rather than a pin, and forcing them to move with
 * the train would be churn with no property behind it. Nor a pin that is the point of its fixture:
 * a manifest line that provokes a version conflict says so with a trailing {@code # off the train:}
 * comment, and the guard leaves that one pin alone.
 */
class JUnitPinParityTest {

    private static final Path REPO = RepoRoot.find(JUnitPinParityTest.class);

    /** The JUnit train's coordinates, as a fixture manifest spells them. */
    private static final Pattern PIN = Pattern.compile(
            "name = \"junit-(?:jupiter|platform-launcher|vintage-engine)\", version = \"=?([0-9][^\"]*)\"");

    /** A pin the fixture wants wrong, said on its own line: {@code … }  # off the train: why}. */
    private static final String OFF_THE_TRAIN = "# off the train:";

    /** {@code org.junit.*} artifacts in the lock, with the version each resolved to. */
    private static final Pattern LOCKED = Pattern.compile(
            "name\\s*=\\s*\"(org\\.junit\\.[^:\"]+:[^:\"]+):jar:\"\\s*\\n\\s*version\\s*=\\s*\"([^\"]+)\"");

    @Test
    void every_fixture_pin_is_the_version_jk_itself_resolved() throws IOException {
        String lockText = Files.readString(REPO.resolve("jk-lock.toml"));
        Set<String> lockedVersions = new TreeSet<>();
        Matcher lm = LOCKED.matcher(lockText);
        int lockedCount = 0;
        while (lm.find()) {
            lockedVersions.add(lm.group(2));
            lockedCount++;
        }
        // Self-fail arm: a regex that has stopped seeing the lock would otherwise report parity
        // over an empty set. The train is seven artifacts today; require most of them.
        assertThat(lockedCount)
                .as("org.junit.* artifacts read from jk-lock.toml — a regex that sees none would pass vacuously")
                .isGreaterThanOrEqualTo(5);
        assertThat(lockedVersions)
                .as("the whole JUnit train resolves to one version in jk-lock.toml")
                .hasSize(1);
        String expected = lockedVersions.iterator().next();

        List<String> wrong = new ArrayList<>();
        int pins = 0;
        for (Path java : testSources()) {
            String src = Files.readString(java);
            Matcher m = PIN.matcher(src);
            while (m.find()) {
                if (restOfLine(src, m.end()).contains(OFF_THE_TRAIN)) continue;
                pins++;
                if (!expected.equals(m.group(1))) {
                    wrong.add("  " + REPO.relativize(java) + ": pins " + m.group(1));
                }
            }
        }
        assertThat(pins)
                .as("fixture manifests pinning the JUnit train — a regex that finds none would pass vacuously")
                .isGreaterThanOrEqualTo(20);
        assertThat(wrong)
                .as(
                        "every fixture pin must be %s, the version jk-lock.toml resolved. Bump the lock"
                                + " deliberately and carry the fixtures with it; do not hand-edit one pin.%n%s",
                        expected, String.join("\n", wrong))
                .isEmpty();
    }

    private static String restOfLine(String src, int from) {
        int nl = src.indexOf('\n', from);
        return nl < 0 ? src.substring(from) : src.substring(from, nl);
    }

    /**
     * Every test source in the tree, pruning output directories rather than filtering them out of
     * the results. {@code Files.walk} descends first and filters after, so it would enter
     * {@code build/tmp/junit-*} while tests running in parallel delete those very directories —
     * the walk then dies on a file that was there a moment ago. Skipping the subtree never looks.
     */
    private static List<Path> testSources() throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(REPO, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean output = OutputDirs.isBuildOutputDir(dir) || name.equals("target") || name.equals(".git");
                return output ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Match path segments, not a literal "/src/test/" substring — Windows toString()
                // uses backslashes, so a slash-only check finds zero fixtures and the guard passes
                // vacuously (or fails the floor assertion).
                if (file.getFileName() != null
                        && file.getFileName().toString().endsWith(".java")
                        && isUnderTestSource(file)) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE; // vanished mid-walk — not this guard's business
            }
        });
        return found;
    }

    /** True when {@code file} sits under {@code src/test} or {@code src/integrationTest}. */
    private static boolean isUnderTestSource(Path file) {
        for (int i = 0; i + 1 < file.getNameCount(); i++) {
            if (!"src".equals(file.getName(i).toString())) continue;
            String next = file.getName(i + 1).toString();
            if ("test".equals(next) || "integrationTest".equals(next)) return true;
        }
        return false;
    }
}
