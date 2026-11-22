// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLinesTest {

    /** A clock that reads {@code now}. */
    static Clock at(AtomicLong now) {
        return new Clock() {
            @Override
            public long millis() {
                return now.get();
            }

            @Override
            public long nanos() {
                return now.get() * 1_000_000;
            }
        };
    }

    private static void write(Path root, String file, String text) throws IOException {
        Path f = root.resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    @Test
    void counts_the_non_blank_lines_of_java_kotlin_and_groovy_sources_outside_build_and_tool_directories(
            @TempDir Path root) throws IOException {
        write(root, "src/main/java/A.java", "class A {\r\n\r\n   \t\r\n  int x;\r\n}");
        write(root, "src/test/kotlin/B.kt", "val b = 1\n\n");
        write(root, "src/main/groovy/C.groovy", "def c\n");
        write(root, "src/main/resources/app.xml", "<a/>\n<b/>\n");
        write(root, "target/generated/D.java", "class D {}\n");
        write(root, "lib/build/E.java", "class E {}\n");
        write(root, ".git/F.java", "class F {}\n");
        write(root, ".jk/G.java", "class G {}\n");

        assertThat(ProjectLines.count(root, 1_000))
                .as("3 in A, 1 in B, 1 in C; resources and build, git and jk directories are not sources")
                .isEqualTo(5);
        assertThat(ProjectLines.count(root, 2)).as("counting stops at the cap").isEqualTo(2);
        assertThat(ProjectLines.count(root.resolve("missing"), 1_000)).isZero();
    }

    @Test
    void a_checkout_is_counted_again_only_after_an_hour(@TempDir Path root) throws IOException {
        write(root, "A.java", "class A {}\n");
        AtomicLong now = new AtomicLong(1_000);
        ProjectLines lines = new ProjectLines(1_000, at(now));
        assertThat(lines.of(root.toString())).isEqualTo(1);

        write(root, "B.java", "class B {}\n");
        now.addAndGet(3_599_999);
        assertThat(lines.of(root.toString())).isEqualTo(1);
        now.incrementAndGet();
        assertThat(lines.of(root.toString())).isEqualTo(2);

        assertThat(lines.of(null)).isZero();
        assertThat(ProjectLines.NONE.of(root.toString())).as("no size known").isZero();
    }
}
