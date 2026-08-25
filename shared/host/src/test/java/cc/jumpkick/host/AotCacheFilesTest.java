// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The AOT lane's shared vocabulary: one marker rule, one presence rule, one refusal rule. */
class AotCacheFilesTest {

    @TempDir
    Path tmp;

    /**
     * The marker is the whole cache file name plus {@code .noaot}, for engine and worker keys
     * alike, and a plain suffix strip takes it back. Two spellings in one directory ({@code
     * <key>.noaot} beside {@code <key>.aot.noaot}) made each sweep blind to the other's markers.
     */
    @Test
    void the_marker_is_the_cache_name_plus_a_suffix_and_is_reversible() {
        Path worker = tmp.resolve("kotlinc-0123456789abcdef.aot");
        Path engine = tmp.resolve("engine-0.12.0-fedcba9876543210.aot");

        assertThat(AotCacheFiles.marker(worker).getFileName()).hasToString("kotlinc-0123456789abcdef.aot.noaot");
        assertThat(AotCacheFiles.marker(engine).getFileName()).hasToString("engine-0.12.0-fedcba9876543210.aot.noaot");

        for (Path cache : new Path[] {worker, engine}) {
            String markerName = AotCacheFiles.marker(cache).getFileName().toString();
            assertThat(AotCacheFiles.isMarker(markerName)).isTrue();
            assertThat(AotCacheFiles.cacheOf(markerName))
                    .isEqualTo(cache.getFileName().toString());
        }
    }

    @Test
    void nothing_else_in_the_aot_directory_reads_as_a_marker() {
        assertThat(AotCacheFiles.isMarker("kotlinc-0123456789abcdef.aot")).isFalse();
        assertThat(AotCacheFiles.isMarker("kotlinc-0123456789abcdef.aot.config"))
                .isFalse();
        assertThat(AotCacheFiles.isMarker("engine-0.12.0-fedcba9876543210.noaot"))
                .isFalse(); // the retired second spelling
        assertThat(AotCacheFiles.isMarker(null)).isFalse();
        assertThat(AotCacheFiles.cacheOf("aot.toml")).isNull();
    }

    @Test
    void a_cache_is_present_only_as_a_non_empty_regular_file() throws IOException {
        Path cache = tmp.resolve("kotlinc-0123456789abcdef.aot");
        assertThat(AotCacheFiles.usable(cache)).isFalse(); // missing
        Files.createFile(cache);
        assertThat(AotCacheFiles.usable(cache)).isFalse(); // zero-byte truncation leftover
        Files.writeString(cache, "aot");
        assertThat(AotCacheFiles.usable(cache)).isTrue();
        assertThat(AotCacheFiles.usable(null)).isFalse();
        assertThat(AotCacheFiles.usable(tmp)).isFalse(); // directory
    }

    @Test
    void deleteIfEmpty_reclaims_only_the_zero_byte_leftover() throws IOException {
        Path empty = Files.createFile(tmp.resolve("kotlinc-aaaaaaaaaaaaaaaa.aot"));
        Path full = Files.writeString(tmp.resolve("kotlinc-bbbbbbbbbbbbbbbb.aot"), "aot");

        AotCacheFiles.deleteIfEmpty(empty);
        AotCacheFiles.deleteIfEmpty(full);
        AotCacheFiles.deleteIfEmpty(tmp.resolve("absent.aot"));

        assertThat(empty).doesNotExist();
        assertThat(full).exists();
    }

    /** The refusal shapes the JVM emits under {@code -Xlog:aot}. */
    @Test
    void a_refused_cache_is_recognised_from_the_aot_log() {
        assertThat(AotCacheFiles.refusal("[0.004s][error  ][aot] shared class paths mismatch"))
                .contains("shared class paths mismatch");
        assertThat(AotCacheFiles.refusal("[0.003s][warning][aot] The AOT cache was created by a"
                        + " different version or build of HotSpot"))
                .contains("different version");
        assertThat(AotCacheFiles.refusal("[0.004s][error][aot] Unable to map shared spaces"))
                .contains("Unable to map");
        assertThat(AotCacheFiles.refusal("[0.003s][error][aot] Loading static archive failed."))
                .contains("failed");
    }

    /**
     * {@code -Xlog} pads the level field to the widest enabled level, so an error arrives as
     * {@code [error  ]} whenever warnings are enabled too — the common shape. A predicate that
     * tested for the literal {@code [error][aot]} therefore missed real refusals.
     */
    @Test
    void the_level_field_is_space_padded_and_still_reads_as_an_error() {
        assertThat(AotCacheFiles.refused("[0.004s][error  ][aot] Unable to map shared spaces"))
                .isTrue();
        assertThat(AotCacheFiles.refused("[0.004s][error][aot] boom")).isTrue();
        assertThat(AotCacheFiles.refused("[0.004s][error  ][aot] boom")).isTrue();
    }

    /** The module-system complaints carry no {@code [aot]} tag, so the tag test cannot gate them. */
    @Test
    void the_untagged_module_mismatches_are_refusals_too() {
        assertThat(AotCacheFiles.refused("Mismatched values for property jdk.module.addmods: ..."))
                .isTrue();
        assertThat(AotCacheFiles.refused("Disabling optimized module handling")).isTrue();
    }

    @Test
    void a_cache_that_maps_reports_no_refusal() {
        assertThat(AotCacheFiles.refusal("[0.008s][info][class,path] Archived app classpath validation: passed\n"
                        + "[0.004s][info][aot] Opened AOT cache app.aot."))
                .isNull();
        // Per-item narration on a run that mapped fine — not a refusal.
        assertThat(AotCacheFiles.refused("[0.010s][info][aot] failed to load class Foo"))
                .isFalse();
        assertThat(AotCacheFiles.refusal("jk engine: spawning /lib/jk-engine.jar (installed)"))
                .isNull();
        assertThat(AotCacheFiles.refusal(null)).isNull();
        assertThat(AotCacheFiles.refusal("")).isNull();
    }

    /**
     * One window for a refusal, whoever asks. The engine's spawn decision used to treat a marker as
     * permanent while the worker trainer expired one after a week, so the same sidecar in the same
     * directory meant two different things depending on which process read it.
     */
    @Test
    void a_refusal_is_believed_for_the_ttl_and_then_deleted(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("engine-0.12.0-0123456789abcdef.aot");
        assertThat(AotCacheFiles.blocked(cache)).isFalse(); // no marker at all
        assertThat(AotCacheFiles.blocked(null)).isFalse();

        Path marker = Files.createFile(AotCacheFiles.marker(cache));
        assertThat(AotCacheFiles.blocked(cache)).isTrue();

        Files.setLastModifiedTime(
                marker, FileTime.fromMillis(System.currentTimeMillis() - AotCacheFiles.MARKER_TTL_MILLIS + 60_000));
        assertThat(AotCacheFiles.blocked(cache)).isTrue();
        assertThat(marker).exists();

        Files.setLastModifiedTime(
                marker, FileTime.fromMillis(System.currentTimeMillis() - AotCacheFiles.MARKER_TTL_MILLIS - 60_000));
        assertThat(AotCacheFiles.blocked(cache)).isFalse();
        // Deleted rather than ignored: the answer and the disk cannot drift apart, and a sweep that
        // never runs cannot bring the refusal back.
        assertThat(marker).doesNotExist();
    }
}
