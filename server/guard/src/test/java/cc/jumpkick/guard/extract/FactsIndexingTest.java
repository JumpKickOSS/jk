// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsIndexing.Ensured;
import cc.jumpkick.guard.extract.fixture.Sample;
import cc.jumpkick.guard.facts.FactsIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactsIndexingTest {

    private static Path classes(Path dir) throws IOException {
        Path pkg = dir.resolve("classes/cc/jumpkick/guard/extract/fixture");
        Files.createDirectories(pkg);
        Files.write(pkg.resolve("Sample.class"), FactsExtractorTest.classBytes(Sample.class));
        Files.write(pkg.resolve("Sample$Inner.class"), FactsExtractorTest.classBytes(Sample.Inner.class));
        return dir.resolve("classes");
    }

    @Test
    void cold_then_fresh_then_incremental(@TempDir Path dir) throws IOException {
        Path classes = classes(dir);
        Path idx = FactsIndexing.indexPath(dir.resolve("target"), "main");

        Ensured cold = FactsIndexing.ensure(classes, idx);
        assertThat(cold.tier()).isEqualTo(Ensured.Tier.COLD);
        assertThat(cold.classes()).isEqualTo(2);
        assertThat(cold.reextracted()).isEqualTo(2);
        assertThat(Files.isRegularFile(idx)).isTrue();

        Ensured fresh = FactsIndexing.ensure(classes, idx);
        assertThat(fresh.tier()).isEqualTo(Ensured.Tier.FRESH);
        assertThat(fresh.bodyDigest()).isEqualTo(cold.bodyDigest());
        assertThat(fresh.reextracted()).isZero();

        // Touch one class: only it is re-read; the digest is unchanged because the bytes are.
        Path inner = classes.resolve("cc/jumpkick/guard/extract/fixture/Sample$Inner.class");
        Files.setLastModifiedTime(
                inner, FileTime.fromMillis(Files.getLastModifiedTime(inner).toMillis() + 5_000));
        Ensured touched = FactsIndexing.ensure(classes, idx);
        assertThat(touched.tier()).isEqualTo(Ensured.Tier.INCREMENTAL);
        assertThat(touched.reextracted()).isEqualTo(1);
        assertThat(touched.bodyDigest()).isEqualTo(cold.bodyDigest());

        // Remove one class: the table shrinks and the digest moves.
        Files.delete(inner);
        Ensured removed = FactsIndexing.ensure(classes, idx);
        assertThat(removed.classes()).isEqualTo(1);
        assertThat(removed.reextracted()).isZero();
        assertThat(removed.bodyDigest()).isNotEqualTo(cold.bodyDigest());

        FactsIndex loaded = FactsIndexing.load(removed);
        assertThat(loaded.classes()).containsOnlyKeys("cc/jumpkick/guard/extract/fixture/Sample");
    }

    @Test
    void no_classes_directory_is_absent_not_an_error(@TempDir Path dir) throws IOException {
        Ensured e = FactsIndexing.ensure(dir.resolve("nope"), dir.resolve("g.idx"));
        assertThat(e.tier()).isEqualTo(Ensured.Tier.ABSENT);
        assertThat(FactsIndexing.load(e).classes()).isEmpty();
        assertThat(Files.exists(dir.resolve("g.idx"))).isFalse();
    }
}
