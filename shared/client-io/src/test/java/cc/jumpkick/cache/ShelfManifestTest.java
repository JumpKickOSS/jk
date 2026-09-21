// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.FakeClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShelfManifestTest {

    private static final String ENGINE_A = "a".repeat(64);
    private static final String ENGINE_B = "b".repeat(64);
    private static final String SHA_1 = "1".repeat(64);
    private static final String SHA_2 = "2".repeat(64);

    @Test
    void a_written_manifest_reads_back_with_every_pin_and_its_provenance(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("lib/jk-engine/" + ShelfManifest.FILE_NAME);
        // A space is all a path can portably carry that still forces the writer to quote: Windows
        // rejects a `"` in a filename outright. MinimalTomlTest covers the escaping itself.
        Path source = tmp.resolve("checkout with spaces");
        FakeClock clock = new FakeClock().set(Instant.parse("2026-09-20T12:00:00Z"));

        ShelfManifest written = ShelfManifest.record(
                file,
                ENGINE_A.toUpperCase(),
                source,
                Map.of("cc.jumpkick:jk-java-compiler:0.13.4", SHA_1, "cc.jumpkick:jk-host:0.13.4", SHA_2.toUpperCase()),
                Map.of("cc.jumpkick:jk-java-compiler:0.13.4", SHA_2),
                clock);

        ShelfManifest read = ShelfManifest.read(file).orElseThrow();
        assertThat(read).isEqualTo(written);
        assertThat(read.engineSha256()).isEqualTo(ENGINE_A);
        assertThat(read.pins(ENGINE_A.toUpperCase())).isTrue();
        assertThat(read.pins(ENGINE_B)).isFalse();
        assertThat(read.source()).isEqualTo(source.toAbsolutePath().normalize().toString());
        assertThat(read.installedAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));
        assertThat(read.sha("cc.jumpkick:jk-java-compiler:0.13.4")).contains(SHA_1);
        assertThat(read.sha("cc.jumpkick:jk-host:0.13.4"))
                .as("shas are stored lower-case")
                .contains(SHA_2);
        assertThat(read.sha("cc.jumpkick:jk-nope:0.13.4")).isEmpty();
        assertThat(read.pomSha("cc.jumpkick:jk-java-compiler:0.13.4")).contains(SHA_2);
        assertThat(read.pomSha("cc.jumpkick:jk-host:0.13.4"))
                .as("a jar may be shelved without a POM")
                .isEmpty();
        assertThat(Files.readString(file))
                .startsWith("engine-sha256 = \"" + ENGINE_A + "\"\n")
                .contains("\n[jars]\n")
                .contains("\n[poms]\n\"cc.jumpkick:jk-java-compiler:0.13.4\" = \"" + SHA_2 + "\"\n");
    }

    @Test
    void recording_for_the_same_engine_merges_and_for_another_engine_replaces(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve(ShelfManifest.FILE_NAME);
        FakeClock clock = new FakeClock();
        Map<String, String> both = Map.of("g:a:1", SHA_1, "g:b:1", SHA_1);
        ShelfManifest.record(file, ENGINE_A, tmp, both, both, clock);

        // A scoped install of one module keeps the pins it did not touch and moves the one it did.
        ShelfManifest same =
                ShelfManifest.record(file, ENGINE_A, tmp, Map.of("g:a:1", SHA_2), Map.of("g:a:1", SHA_2), clock);
        assertThat(same.jars()).containsExactlyInAnyOrderEntriesOf(Map.of("g:a:1", SHA_2, "g:b:1", SHA_1));
        assertThat(same.poms()).containsExactlyInAnyOrderEntriesOf(Map.of("g:a:1", SHA_2, "g:b:1", SHA_1));

        // Another engine's install starts from nothing: its shelf is what it shelved.
        ShelfManifest other =
                ShelfManifest.record(file, ENGINE_B, tmp.resolve("other"), Map.of("g:a:1", SHA_1), Map.of(), clock);
        assertThat(other.jars()).containsExactlyEntriesOf(Map.of("g:a:1", SHA_1));
        assertThat(other.poms()).isEmpty();
        assertThat(ShelfManifest.read(file).orElseThrow().engineSha256()).isEqualTo(ENGINE_B);
    }

    @Test
    void a_manifest_that_names_no_engine_or_is_absent_reads_as_nothing(@TempDir Path tmp) throws Exception {
        assertThat(ShelfManifest.read(tmp.resolve("missing.toml"))).isEmpty();
        assertThat(ShelfManifest.parse(List.of("source = \"/x\"", "[jars]", "\"g:a:1\" = \"" + SHA_1 + "\"")))
                .isEmpty();
        assertThat(ShelfManifest.parse(List.of("engine-sha256 = \"not-a-sha\"")))
                .isEmpty();
        // A malformed pin is dropped; the manifest still stands for its engine.
        ShelfManifest partial = ShelfManifest.parse(List.of(
                        "engine-sha256 = \"" + ENGINE_A + "\"",
                        "[jars]",
                        "\"g:a:1\" = \"" + SHA_1 + "\"",
                        "\"g:b:1\" = \"short\""))
                .orElseThrow();
        assertThat(partial.jars()).containsOnlyKeys("g:a:1");
        assertThat(partial.source()).isEmpty();
    }

    @Test
    void a_key_that_is_not_a_plain_coordinate_is_refused_on_write_and_skipped_on_read(@TempDir Path tmp) {
        String painted = "\u001b[38;2;0;212;224mcc.jumpkick\u001b[0m:jk-java-compiler:0.13.4";
        assertThat(ShelfManifest.isCoordinate(painted)).isFalse();
        assertThat(ShelfManifest.isCoordinate("cc.jumpkick:jk-java-compiler:0.13.4"))
                .isTrue();
        assertThat(ShelfManifest.isCoordinate("g:a")).isFalse();
        assertThat(ShelfManifest.isCoordinate("g:a :1")).isFalse();

        assertThatThrownBy(() -> new ShelfManifest(ENGINE_A, "", Instant.EPOCH, Map.of(painted, SHA_1), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group:artifact:version");

        ShelfManifest read = ShelfManifest.parse(List.of(
                        "engine-sha256 = \"" + ENGINE_A + "\"",
                        "[jars]",
                        "\"\\u001b[36mg\\u001b[0m:a:1\" = \"" + SHA_1 + "\"",
                        "\"g:b:1\" = \"" + SHA_2 + "\"",
                        "[poms]",
                        "\"g:b:1\" = \"" + SHA_1 + "\"",
                        "\"g c:b:1\" = \"" + SHA_1 + "\""))
                .orElseThrow();
        assertThat(read.jars()).containsExactly(Map.entry("g:b:1", SHA_2));
        assertThat(read.poms()).containsExactly(Map.entry("g:b:1", SHA_1));
    }
}
