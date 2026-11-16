// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.ClassCones;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A class's key moves with what it read; past its record's bound it moves with everything its suite
 * read; it falls back to its module when it loaded a workspace class its cone does not name, started
 * a program, loaded past its bound or has no record.
 */
class ClassKeysTest {

    @Test
    void a_class_is_keyed_by_its_cone_and_reads_until_it_cannot_be_trusted(@TempDir Path ws) throws Exception {
        Path classes = Files.createDirectories(ws.resolve("classes"));
        ClassCones.Cone cone = ClassCones.index(List.of(classes)).cone("demo.T");
        Path data = Files.writeString(ws.resolve("data.txt"), "one");
        ClassKeys.Observed seen = observed(List.of("data.txt"), false, List.of());
        ClassKeys.Key key = key(cone, seen, "suite-reads", ws);
        Files.writeString(data, "two");
        assertThat(key(cone, seen, "suite-reads", ws).key())
                .as("a file it read changed")
                .isNotEqualTo(key.key());

        ClassKeys.Observed many = observed(List.of("data.txt"), true, List.of());
        String before = key(cone, many, "reads-1", ws).key();
        assertThat(key(cone, many, "reads-2", ws).key())
                .as("past its bound, any file its suite read")
                .isNotEqualTo(before);
        assertThat(key(cone, seen, "reads-2", ws).key())
                .as("within its bound, only its own")
                .isEqualTo(key(cone, seen, "reads-1", ws).key());

        assertThat(ClassKeys.fallback(cone, seen, List.of("demo.B"), false, "demo.T", Set.of("demo.B")::contains))
                .as("a workspace class loaded outside its cone")
                .isEqualTo("reflective demo.B");
        assertThat(ClassKeys.fallback(cone, seen, List.of("demo.Gone"), false, "demo.T", Set.of()::contains))
                .as("a class gone from the workspace moved the cone itself")
                .isNull();
        assertThat(ClassKeys.fallback(
                        cone,
                        observed(List.of(), false, List.of("/usr/bin/git")),
                        List.of(),
                        false,
                        "demo.T",
                        c -> true))
                .startsWith("process");
        assertThat(ClassKeys.fallback(cone, seen, List.of(), true, "demo.T", c -> true))
                .startsWith("loads past");
        assertThat(ClassKeys.fallback(cone, ClassKeys.Observed.NONE, List.of(), false, "demo.T", c -> true))
                .isEqualTo("unattributed");
    }

    private static ClassKeys.Observed observed(List<String> reads, boolean more, List<String> processes) {
        return new ClassKeys.Observed(true, reads, more, List.of(), false, processes);
    }

    private static ClassKeys.Key key(ClassCones.Cone cone, ClassKeys.Observed seen, String suiteReads, Path ws) {
        return ClassKeys.key("", cone, "suite", seen, ClassKeys.Observed.NONE, "module", suiteReads, ws, null);
    }
}
