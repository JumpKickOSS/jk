// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SnapshotFilesTest {

    private static final String DIR = "cc/example/host/1.0.1-SNAPSHOT/";

    private static byte[] metadata(String versioning) {
        return ("<metadata><versioning>" + versioning + "</versioning></metadata>").getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] UNIQUE = metadata("""
            <snapshot><timestamp>20261025.201500</timestamp><buildNumber>3</buildNumber></snapshot>
            <snapshotVersions>
              <snapshotVersion><extension>jar</extension><value>1.0.1-20261025.201500-3</value></snapshotVersion>
              <snapshotVersion><extension>pom</extension><value>1.0.1-20261025.201500-3</value></snapshotVersion>
              <snapshotVersion><classifier>sources</classifier><extension>jar</extension>
                <value>1.0.1-20261025.201400-2</value></snapshotVersion>
            </snapshotVersions>
            """);

    @Test
    void a_file_takes_the_value_listed_for_its_classifier_and_extension() {
        assertThat(SnapshotFiles.stamped(DIR + "host-1.0.1-SNAPSHOT.pom", UNIQUE))
                .contains(DIR + "host-1.0.1-20261025.201500-3.pom");
        assertThat(SnapshotFiles.stamped(DIR + "host-1.0.1-SNAPSHOT-sources.jar", UNIQUE))
                .contains(DIR + "host-1.0.1-20261025.201400-2-sources.jar");
    }

    @Test
    void a_file_the_list_leaves_out_takes_the_newest_build() {
        assertThat(SnapshotFiles.stamped(DIR + "host-1.0.1-SNAPSHOT.module", UNIQUE))
                .contains(DIR + "host-1.0.1-20261025.201500-3.module");
    }

    @Test
    void a_local_copy_or_a_release_keeps_its_name() {
        byte[] local = metadata("<snapshot><localCopy>true</localCopy></snapshot>");
        assertThat(SnapshotFiles.stamped(DIR + "host-1.0.1-SNAPSHOT.pom", local))
                .isEmpty();
        assertThat(SnapshotFiles.stamped("cc/example/host/1.0.1/host-1.0.1.pom", UNIQUE))
                .isEmpty();
        assertThat(SnapshotFiles.stamped(DIR + "host-1.0.1-SNAPSHOT.pom", "not xml".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    void the_metadata_sits_in_the_version_directory() {
        assertThat(SnapshotFiles.metadataPath(DIR + "host-1.0.1-SNAPSHOT.pom")).isEqualTo(DIR + "maven-metadata.xml");
    }
}
