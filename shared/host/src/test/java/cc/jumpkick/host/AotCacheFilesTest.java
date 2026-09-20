// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The AOT lane's shared vocabulary: one marker rule, one presence rule, one refusal rule. */
class AotCacheFilesTest {

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
        // A classpath entry that changed since training, as JDK 25.0.4 words it at warning level.
        assertThat(AotCacheFiles.refusal("[0.008s][warning][aot] This file is not the one used while"
                        + " building the AOT cache: 'lib/app.jar', timestamp has changed, size has changed"))
                .contains("not the one used while building");
    }

    /**
     * The whole transcript of a JDK 25.0.4 refusal (a jar changed after training): the first
     * line that proves the refusal is the answer, and the app's own output is not.
     */
    @Test
    void a_real_refusal_transcript_names_its_first_proving_line() {
        String log = "[0.006s][info][aot] trying to map app.aot\n"
                + "[0.006s][info][aot] Opened AOT cache app.aot.\n"
                + "[0.008s][warning][aot] This file is not the one used while building the AOT cache:"
                + " 'app.jar', timestamp has changed, size has changed\n"
                + "[0.008s][error  ][aot] An error has occurred while processing the AOT cache."
                + " Run with -Xlog:aot for details.\n"
                + "[0.008s][error  ][aot] shared class paths mismatch (hint: enable -Xlog:class+path=info"
                + " to diagnose the failure)\n"
                + "[0.009s][error  ][aot] Unable to map shared spaces\n"
                + "hello\n";
        assertThat(AotCacheFiles.refusal(log)).contains("shared class paths mismatch");
    }

    /**
     * JDK 25.0.4 maps the cache and then warns about one adapter blob whose saved name disagrees
     * with the recomputed one — the two differ by a character. The cache is in use (the app
     * starts several times faster than cold), so neither line is a refusal, alone or in the full
     * transcript. Both carry {@code cache} and one carries {@code failed}: a substring pair is
     * not a refusal shape.
     */
    @Test
    void the_adapter_blob_warning_of_a_mapped_cache_is_not_a_refusal() {
        String saved = "[0.031s][warning][aot,codecache] Saved blob's name 'LLLLLLIILLLL' is different"
                + " from the expected name 'LLLLLLLIILLL'";
        String link = "[0.031s][warning][aot] Failed to link AdapterHandlerEntry (fp=LLLLLLIILLLL) to its"
                + " code in the AOT code cache";
        assertThat(AotCacheFiles.refusal(saved)).isNull();
        assertThat(AotCacheFiles.refusal(link)).isNull();
        assertThat(AotCacheFiles.refusal("[0.004s][info][aot] Opened AOT cache app.aot.\n"
                        + saved + "\n" + link + "\n"
                        + "[0.040s][info][aot] Using AOT-linked classes: true (static archive: has aot-linked classes)\n"
                        + "Started in 260 ms\n"))
                .isNull();
    }

    /**
     * The JVM's own "in use" line is the verdict when it is present: a warning-level line that
     * merely resembles a disable shape cannot override it. The same line without that evidence
     * still refuses, and an error-level {@code [aot]} line refuses regardless.
     */
    @Test
    void the_mapped_signal_outranks_a_warning_shaped_line_but_not_an_error() {
        String codeCacheDisabled = "[0.020s][warning][aot,codecache] AOT Code Cache disabled: it was created"
                + " with different GC: G1 vs current Serial";
        String mapped = "[0.021s][info][aot] Using AOT-linked classes: true (static archive: has aot-linked classes)";
        assertThat(AotCacheFiles.refusal(codeCacheDisabled + "\n" + mapped + "\n"))
                .isNull();
        assertThat(AotCacheFiles.refusal(codeCacheDisabled)).contains("AOT Code Cache disabled");
        assertThat(AotCacheFiles.refusal(mapped + "\n[0.022s][error  ][aot] Unable to map shared spaces\n"))
                .contains("Unable to map");
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
}
