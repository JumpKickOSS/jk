// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void the_stamped_sources_are_every_stamp_s_source_lines(@TempDir Path dir) throws IOException {
        assertThat(BuildStamps.stampedSources(dir)).isEmpty();
        Path java = dir.resolve("src/main/java/p/A Space.java");
        Path kotlin = dir.resolve("src/main/kotlin/K.kt");
        Files.writeString(
                dir.resolve(BuildStamps.JAVA),
                "TASK t\nKEY k\n" + BuildStamps.SOURCE_LINE + "none " + java + "\nCP x\n");
        Files.writeString(dir.resolve(BuildStamps.KOTLIN), BuildStamps.SOURCE_LINE + "1:2:3 " + kotlin + "\n");
        assertThat(BuildStamps.stampedSources(dir)).containsExactly(java, kotlin);
    }
}
