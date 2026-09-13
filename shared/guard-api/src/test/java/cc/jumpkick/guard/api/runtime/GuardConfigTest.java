// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class GuardConfigTest {

    @TempDir
    Path dir;

    private GuardConfig full() {
        return new GuardConfig(
                dir.resolve("out/guard/report.jsonl"),
                dir,
                "server/engine",
                List.of(dir.resolve("main.idx"), dir.resolve("other.idx")),
                List.of(dir.resolve("test.idx")),
                dir.resolve("model.json"),
                List.of(dir.resolve("src"), dir.resolve("src2")),
                List.of(dir.resolve("classes")),
                List.of(dir.resolve("a.pom")),
                List.of(dir.resolve("a.jar"), dir.resolve("b.jar")),
                dir.resolve("coverage.xml"),
                true,
                null);
    }

    @Test
    void every_field_round_trips_including_the_optional_ones() throws Exception {
        Path f = dir.resolve("run.properties");
        Files.writeString(f, full().toProperties());
        GuardConfig back = GuardConfig.read(f);
        assertThat(back).isEqualTo(full());
        assertThat(back.model()).isEqualTo(dir.resolve("model.json"));
        assertThat(back.coverage()).isEqualTo(dir.resolve("coverage.xml"));
        assertThat(back.fixture()).isTrue();
        assertThat(back.jars()).containsExactly(dir.resolve("a.jar"), dir.resolve("b.jar"));
    }

    @Test
    void absent_optionals_are_null_and_absent_lists_are_empty() throws Exception {
        Path f = dir.resolve("min.properties");
        Files.writeString(
                f,
                "report="
                        + dir.resolve("r.jsonl")
                                .toString()
                                .replace("\\", "\\\\")
                                .replace(":", "\\:") + "\nroot="
                        + dir.toString().replace("\\", "\\\\").replace(":", "\\:") + "\n");
        GuardConfig c = GuardConfig.read(f);
        assertThat(c.module()).isEmpty();
        assertThat(c.facts()).isEmpty();
        assertThat(c.testFacts()).isEmpty();
        assertThat(c.model()).isNull();
        assertThat(c.coverage()).isNull();
        assertThat(c.sources()).isEmpty();
        assertThat(c.fixture()).isFalse();
    }

    @Test
    void a_missing_required_key_names_the_key() throws Exception {
        Path f = dir.resolve("bad.properties");
        Files.writeString(f, "root=/tmp\n");
        assertThatThrownBy(() -> GuardConfig.read(f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("`report`");
        Files.writeString(f, "report=/tmp/r\nroot=   \n");
        assertThatThrownBy(() -> GuardConfig.read(f)).hasMessageContaining("`root`");
    }

    @Test
    void the_properties_text_escapes_what_properties_files_treat_specially() throws Exception {
        // A colon is illegal in a Windows filename, yet every Windows path carries one in its drive
        // letter — so the colon under test comes from the name on POSIX and from the root here.
        boolean windows = OS.WINDOWS.isCurrentOs();
        Path odd = dir.resolve(windows ? "a=b" : "a=b:c");
        GuardConfig c = new GuardConfig(
                odd.resolve("r.jsonl"),
                odd,
                "",
                List.of(odd.resolve("x.idx")),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                null);
        String text = c.toProperties();
        assertThat(text).contains(windows ? "\\=b" : "\\=b\\:c");
        assertThat(text).as("a colon is escaped wherever it comes from").contains("\\:");
        assertThat(text).doesNotContain("model=").doesNotContain("coverage=");
        Path f = dir.resolve("odd.properties");
        Files.writeString(f, text);
        assertThat(GuardConfig.read(f)).isEqualTo(c);
    }

    @Test
    void a_text_root_survives_the_round_trip_and_is_absent_by_default() throws Exception {
        GuardConfig plain = new GuardConfig(
                dir.resolve("r.jsonl"),
                dir,
                "",
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                null);
        assertThat(plain.toProperties()).doesNotContain("text-root=");
        Path tree = dir.resolve("fixtures/ci-cadence/Bad-missing-pin/tree");
        GuardConfig rooted = new GuardConfig(
                dir.resolve("r.jsonl"),
                dir,
                "",
                List.of(),
                List.of(),
                null,
                List.of(tree),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                tree);
        Path f = dir.resolve("rooted.properties");
        Files.writeString(f, rooted.toProperties());
        assertThat(GuardConfig.read(f)).isEqualTo(rooted);
        assertThat(GuardConfig.read(f).textRoot()).isEqualTo(tree);
    }

    @Test
    void lists_are_pipe_separated_and_blank_segments_are_dropped() throws Exception {
        Path f = dir.resolve("pipes.properties");
        Files.writeString(f, "report=/r\nroot=/\nfacts=/a.idx||/b.idx|\njars=\n");
        GuardConfig c = GuardConfig.read(f);
        assertThat(c.facts()).containsExactly(Path.of("/a.idx"), Path.of("/b.idx"));
        assertThat(c.jars()).isEmpty();
        // The rendering of a path is the platform's; what this pins is the joining — one pipe
        // between two segments, and no empty segment where the input had a run of them.
        String facts = c.toProperties()
                .lines()
                .filter(l -> l.startsWith("facts="))
                .findFirst()
                .orElseThrow();
        assertThat(facts.substring("facts=".length()).split("\\|", -1))
                .containsExactly(rendered(Path.of("/a.idx")), rendered(Path.of("/b.idx")));
    }

    /** A path as the properties text carries it: the platform's separators, escaped. */
    private static String rendered(Path p) {
        return p.toString().replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=");
    }

    @Test
    void the_system_property_name_is_the_engine_contract() {
        assertThat(GuardConfig.PROPERTY).isEqualTo("jk.guard.config");
    }
}
