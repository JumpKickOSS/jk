// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Whether a host is this machine has one answer, {@link RepositorySpec#loopback} (and {@link
 * RepositorySpec#loopbackAuthority} for a {@code host:port}): an address literal in 127/8 or
 * {@code ::1}, in every spelling, no lookup. A string comparison against a loopback literal
 * anywhere else in production code is a second answer that misses the mapped and 127/8 forms.
 */
class OneLoopbackTest {

    /** {@code equals("127.0.0.1")}, {@code startsWith("127.")}, {@code equals("::1")}, {@code equals("[::1]")}. */
    private static final Pattern LITERAL_TEST =
            Pattern.compile("(?:equals|equalsIgnoreCase|startsWith)\\(\"(?:127\\.|\\[?::1|0:0:0:0:0:0:0:1)");

    /** The trees jk builds its own production code from. */
    private static final List<String> TREES =
            List.of("shared", "server", "clients/cli", "clients/cli-terminal", "plugins");

    @Test
    void only_repository_spec_decides_what_is_loopback() throws IOException {
        Path root = RepoRoot.find(OneLoopbackTest.class);
        List<String> found = new ArrayList<>();
        int walked = 0;
        for (String tree : TREES) {
            Path dir = root.resolve(tree);
            if (!Files.isDirectory(dir)) continue;
            for (Path file : productionSources(dir)) {
                walked++;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (LITERAL_TEST.matcher(lines.get(i)).find()) {
                        found.add(root.relativize(file) + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(walked).as("production sources walked").isGreaterThan(1_000);
        assertThat(found)
                .as("a loopback test outside RepositorySpec misses [::ffff:127.0.0.1] and the rest of 127/8")
                .isEmpty();
    }

    /** The probe above must see the shapes it looks for, or an empty result proves nothing. */
    @Test
    void the_probe_sees_each_literal_test() {
        assertThat(LITERAL_TEST.matcher("name.equals(\"127.0.0.1\")").find()).isTrue();
        assertThat(LITERAL_TEST.matcher("host.startsWith(\"127.\")").find()).isTrue();
        assertThat(LITERAL_TEST.matcher("name.equals(\"::1\")").find()).isTrue();
        assertThat(LITERAL_TEST.matcher("name.equals(\"[::1]\")").find()).isTrue();
        assertThat(LITERAL_TEST
                        .matcher("URI.create(\"http://127.0.0.1:\" + port)")
                        .find())
                .isFalse();
    }

    /**
     * Every {@code .java} file under a {@code src/main/java} root below {@code dir}. A module's
     * {@code target/} (its own top-level build output, a sibling of {@code src/}) is not walked.
     */
    private static List<Path> productionSources(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                Path name = d.getFileName();
                boolean output = name != null
                        && name.toString().equals("target")
                        && Files.isRegularFile(d.resolveSibling("jk.toml"));
                return output ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                String path = f.toString().replace('\\', '/');
                if (path.endsWith(".java") && path.contains("/src/main/java/")) out.add(f);
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}
