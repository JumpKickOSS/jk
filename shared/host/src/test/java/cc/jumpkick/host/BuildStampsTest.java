// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildStampsTest {

    @Test
    void every_stamp_name_is_a_stamp_file() {
        assertThat(BuildStamps.ALL)
                .allSatisfy(name ->
                        assertThat(BuildStamps.isStampFile(name)).as(name).isTrue());
        assertThat(BuildStamps.ALL).containsExactly(".jstamp", ".kstamp", ".gstamp", ".kspstamp");
    }

    @Test
    void an_archive_entry_path_is_matched_by_its_last_segment() {
        // The packagers ask with a jar-relative name, not a bare file name.
        assertThat(BuildStamps.isStampFile("BOOT-INF/classes/.gstamp")).isTrue();
        assertThat(BuildStamps.isStampFile("com/example/App.class")).isFalse();
    }

    @Test
    void a_file_that_merely_ends_in_the_suffix_is_not_a_stamp() {
        assertThat(BuildStamps.isStampFile("Main.jstamp")).isFalse();
        assertThat(BuildStamps.isStampFile("Main.class")).isFalse();
        assertThat(BuildStamps.isStampFile("")).isFalse();
    }
}
