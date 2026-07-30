// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JK-1256: {@code jk publish} must emit an artifact-level {@code maven-metadata.xml}, or a
 * published artifact cannot be resolved from a plain {@code file://} / static-HTTP / object-store
 * repo — those have no server side to synthesize it.
 */
class MavenMetadataTest {

    @Test
    void first_publish_lists_the_single_version() {
        String xml = MavenPublisher.metadataXml("app.knest", "knest-core", List.of("0.1.0"));
        assertThat(xml).contains("<groupId>app.knest</groupId>");
        assertThat(xml).contains("<artifactId>knest-core</artifactId>");
        assertThat(xml).contains("<latest>0.1.0</latest>");
        assertThat(xml).contains("<release>0.1.0</release>");
        assertThat(xml).contains("<version>0.1.0</version>");
    }

    @Test
    void existing_versions_are_merged_not_clobbered() {
        String existing = MavenPublisher.metadataXml("g", "a", List.of("0.1.0", "0.2.0"));
        List<String> parsed = MavenPublisher.parseVersions(existing);
        assertThat(parsed).containsExactly("0.1.0", "0.2.0");

        // What publishMetadata does with a newly published version.
        parsed.add("0.3.0");
        String merged = MavenPublisher.metadataXml("g", "a", parsed);
        assertThat(MavenPublisher.parseVersions(merged)).containsExactly("0.1.0", "0.2.0", "0.3.0");
        assertThat(merged).contains("<latest>0.3.0</latest>");
    }

    @Test
    void release_skips_snapshots_but_latest_does_not() {
        String xml = MavenPublisher.metadataXml("g", "a", List.of("1.0.0", "1.1.0-SNAPSHOT"));
        assertThat(xml).contains("<latest>1.1.0-SNAPSHOT</latest>");
        assertThat(xml).contains("<release>1.0.0</release>");
    }

    @Test
    void a_snapshot_only_artifact_omits_release() {
        String xml = MavenPublisher.metadataXml("g", "a", List.of("0.1.0-SNAPSHOT"));
        assertThat(xml).contains("<latest>0.1.0-SNAPSHOT</latest>");
        assertThat(xml).doesNotContain("<release>");
    }

    @Test
    void parsing_tolerates_absent_and_empty_metadata() {
        assertThat(MavenPublisher.parseVersions("")).isEmpty();
        assertThat(MavenPublisher.parseVersions("<metadata><versioning><versions/></versioning></metadata>"))
                .isEmpty();
    }

    @Test
    void parsing_is_duplicate_free() {
        String xml = "<versions><version>1.0</version><version>1.0</version></versions>";
        assertThat(MavenPublisher.parseVersions(xml)).containsExactly("1.0");
    }
}
