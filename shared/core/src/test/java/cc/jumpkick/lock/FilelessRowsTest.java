// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.model.Scope;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A lock an earlier writer left with unmarked file-less rows gets its marks without a resolve. */
class FilelessRowsTest {

    @Test
    void an_unmarked_checksum_less_row_of_an_earlier_writer_needs_a_mark_and_gets_its_pom_file() {
        Lockfile earlier = lock(
                        row("com.foo:a:jar:", "1.0", "sha256:aaaa", null),
                        row("org.picketbox:picketbox:jar:", "5.0.3.Final", null, null),
                        row("com.foo:bom:pom:", "2.0", null, null),
                        row("com.foo:kmp:jar:", "3.0", null, "kmp-3.0.module"))
                .withWriterBuild(new WriterBuild("older", Lockfile.FILELESS_ROWS_MARKED_SINCE.minusSeconds(1)));
        assertThat(FilelessRows.needsMarks(earlier)).isTrue();

        Lockfile marked = FilelessRows.marked(earlier);
        assertThat(marked.artifacts())
                .extracting(Lockfile.Artifact::name, Lockfile.Artifact::path)
                .containsExactly(
                        tuple("com.foo:a:jar:", null),
                        tuple("org.picketbox:picketbox:jar:", "picketbox-5.0.3.Final.pom"),
                        tuple("com.foo:bom:pom:", null),
                        tuple("com.foo:kmp:jar:", "kmp-3.0.module"));
        assertThat(marked.artifacts().get(1).source())
                .as("the row keeps the repository that served its POM")
                .isEqualTo("jumpkick+https://jumpkick.build/repo/");
        assertThat(FilelessRows.needsMarks(marked))
                .as("once every checksum-less row names its file there is nothing left to mark")
                .isFalse();
    }

    @Test
    void a_lock_whose_writer_marks_rows_or_that_has_no_such_row_needs_nothing() {
        Lockfile marking = lock(row("org.picketbox:picketbox:jar:", "5.0.3.Final", null, null))
                .withWriterBuild(new WriterBuild("marking", Lockfile.FILELESS_ROWS_MARKED_SINCE));
        assertThat(FilelessRows.needsMarks(marking))
                .as("a checksum-less row a marking writer left bare is a jar nobody fetched, not a mark to add")
                .isFalse();
        assertThat(FilelessRows.needsMarks(lock(row("com.foo:a:jar:", "1.0", "sha256:aaaa", null))))
                .isFalse();
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk 0.1", "pubgrub-v1", List.of(rows));
    }

    private static Lockfile.Artifact row(
            String name, String version, @Nullable String checksum, @Nullable String path) {
        return new Lockfile.Artifact(
                name, version, "jumpkick+https://jumpkick.build/repo/", checksum, path, List.of(Scope.MAIN), List.of());
    }
}
