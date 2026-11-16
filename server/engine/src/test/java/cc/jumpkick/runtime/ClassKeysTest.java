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
 * A class's key moves with what it read, falls back to its module when it loaded a workspace class
 * its cone does not name, started a program or has no record, and moves, without falling back, when
 * a class it loaded is gone.
 */
class ClassKeysTest {

    private static final ClassCones.Cone CONE = new ClassCones.Cone("cone", Set.of("demo.T", "demo.A"), null);

    @Test
    void a_class_is_keyed_by_its_cone_and_reads_until_it_cannot_be_trusted(@TempDir Path ws) throws Exception {
        Path data = Files.writeString(ws.resolve("data.txt"), "one");
        ClassKeys.Observed seen = observed(true, List.of("data.txt"), List.of(), Set.of("demo.A"));
        ClassKeys.Key key = key(seen, Set.of("demo.T", "demo.A", "demo.B"), ws);
        assertThat(key.fallback()).isNull();
        Files.writeString(data, "two");
        assertThat(key(seen, Set.of("demo.T", "demo.A", "demo.B"), ws).key())
                .as("a file it read changed")
                .isNotEqualTo(key.key());

        assertThat(key(observed(true, List.of(), List.of(), Set.of("demo.B")), Set.of("demo.B"), ws)
                        .fallback())
                .as("a workspace class loaded outside its cone")
                .isEqualTo("reflective demo.B");
        assertThat(key(observed(true, List.of(), List.of("/usr/bin/git"), Set.of()), Set.of(), ws)
                        .fallback())
                .startsWith("process");
        assertThat(key(ClassKeys.Observed.NONE, Set.of(), ws).fallback()).isEqualTo("unattributed");

        ClassKeys.Observed loadedGone = observed(true, List.of(), List.of(), Set.of("demo.Gone"));
        ClassKeys.Key gone = key(loadedGone, Set.of("demo.T", "demo.A"), ws);
        assertThat(gone.fallback())
                .as("a class gone from the workspace is no reflection")
                .isNull();
        assertThat(gone.key())
                .as("but it moved under the class")
                .isNotEqualTo(key(observed(true, List.of(), List.of(), Set.of()), Set.of("demo.T"), ws)
                        .key());
    }

    private static ClassKeys.Observed observed(
            boolean attributed, List<String> reads, List<String> processes, Set<String> loaded) {
        return new ClassKeys.Observed(attributed, reads, List.of(), processes, loaded);
    }

    private static ClassKeys.Key key(ClassKeys.Observed seen, Set<String> indexed, Path ws) {
        return ClassKeys.key(
                "demo.T", "", CONE, "suite", seen, ClassKeys.Observed.NONE, "module", indexed::contains, ws);
    }
}
