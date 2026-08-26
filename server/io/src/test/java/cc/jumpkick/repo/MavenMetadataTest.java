// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link MavenMetadata} owns both directions of {@code maven-metadata.xml}, and the reason it has
 * to is here: {@link MavenMetadata#render} and {@link MavenMetadata#parse} must invert each other
 * exactly. Two writers meant one of them escaped and the other did not, and a hand-rolled reader
 * meant a republish could double-escape what the last one wrote.
 */
class MavenMetadataTest {

    private static String text(MavenMetadata md) {
        return new String(md.render(), StandardCharsets.UTF_8);
    }

    private static MavenMetadata reread(MavenMetadata md) {
        return MavenMetadata.parse(md.render());
    }

    @Test
    void a_first_version_lists_itself_as_both_latest_and_release() {
        String xml = text(MavenMetadata.empty("app.knest", "knest-core").withVersion("0.1.0"));
        assertThat(xml).contains("<groupId>app.knest</groupId>");
        assertThat(xml).contains("<artifactId>knest-core</artifactId>");
        assertThat(xml).contains("<latest>0.1.0</latest>");
        assertThat(xml).contains("<release>0.1.0</release>");
        assertThat(xml).contains("<version>0.1.0</version>");
    }

    @Test
    void existing_versions_are_merged_not_clobbered() {
        MavenMetadata two = MavenMetadata.empty("g", "a").withVersion("0.1.0").withVersion("0.2.0");
        assertThat(reread(two).versions()).containsExactly("0.1.0", "0.2.0");

        MavenMetadata three = reread(two).withVersion("0.3.0");
        assertThat(three.versions()).containsExactly("0.1.0", "0.2.0", "0.3.0");
        assertThat(text(three)).contains("<latest>0.3.0</latest>");
    }

    @Test
    void a_version_already_listed_is_not_added_twice() {
        MavenMetadata md = MavenMetadata.empty("g", "a").withVersion("1.0").withVersion("1.0");
        assertThat(md.versions()).containsExactly("1.0");
    }

    @Test
    void versions_are_version_sorted_not_publish_ordered() {
        MavenMetadata md = MavenMetadata.empty("g", "a")
                .withVersion("1.10.0")
                .withVersion("1.9.0")
                .withVersion("1.2.0");
        assertThat(md.versions()).containsExactly("1.2.0", "1.9.0", "1.10.0");
        assertThat(md.latest()).isEqualTo("1.10.0");
    }

    @Test
    void release_skips_snapshots_but_latest_does_not() {
        String xml = text(MavenMetadata.empty("g", "a").withVersion("1.0.0").withVersion("1.1.0-SNAPSHOT"));
        assertThat(xml).contains("<latest>1.1.0-SNAPSHOT</latest>");
        assertThat(xml).contains("<release>1.0.0</release>");
    }

    @Test
    void a_snapshot_only_artifact_omits_release() {
        String xml = text(MavenMetadata.empty("g", "a").withVersion("0.1.0-SNAPSHOT"));
        assertThat(xml).contains("<latest>0.1.0-SNAPSHOT</latest>");
        assertThat(xml).doesNotContain("<release>");
    }

    @Test
    void parsing_an_empty_version_list_yields_no_versions() {
        byte[] xml = "<metadata><artifactId>a</artifactId><versioning><versions/></versioning></metadata>"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(MavenMetadata.parse(xml).versions()).isEmpty();
    }

    @Test
    void coordinates_are_entity_escaped_like_the_pom_published_beside_them() {
        String xml = text(MavenMetadata.empty("a&b", "c<d").withVersion("1.0"));
        assertThat(xml).contains("<groupId>a&amp;b</groupId>");
        assertThat(xml).contains("<artifactId>c&lt;d</artifactId>");
        assertThat(xml).doesNotContain("<groupId>a&b</groupId>");
    }

    @Test
    void a_version_needing_escaping_is_escaped_in_every_element_that_carries_it() {
        String xml = text(MavenMetadata.empty("g", "a").withVersion("1.0&x"));
        assertThat(xml).contains("<version>1.0&amp;x</version>");
        assertThat(xml).contains("<latest>1.0&amp;x</latest>");
        assertThat(xml).contains("<release>1.0&amp;x</release>");
    }

    /**
     * All five predefined entities, in one version string, through the full cycle. A git tag is
     * free-form text that reaches the version verbatim, so this is a document jk really can be
     * asked to write — and if either direction is wrong the publish emits XML no resolver reads.
     */
    @Test
    void every_xml_metacharacter_survives_render_then_parse() {
        String hostile = "1.0-a&b<c>d\"e'f";
        MavenMetadata written = MavenMetadata.empty("g&roup", "art<ifact").withVersion(hostile);

        MavenMetadata read = reread(written);
        assertThat(read.versions()).containsExactly(hostile);
        assertThat(read.latest()).isEqualTo(hostile);
        assertThat(read.release()).isEqualTo(hostile);
        assertThat(read.groupId()).isEqualTo("g&roup");
        assertThat(read.artifactId()).isEqualTo("art<ifact");
    }

    /** Read and write must invert, or each republish re-escapes what the last one wrote. */
    @Test
    void a_republish_of_an_escaped_version_does_not_double_escape() {
        MavenMetadata first = MavenMetadata.empty("g", "a").withVersion("1.0&x");
        MavenMetadata second = reread(first).withVersion("1.0&x");

        assertThat(second.versions()).containsExactly("1.0&x");
        assertThat(text(second)).isEqualTo(text(first)).doesNotContain("&amp;amp;");
    }

    /** A parsed document renders back byte-identical: no field is dropped and none is invented. */
    @Test
    void parse_then_render_is_a_fixed_point() {
        byte[] once = MavenMetadata.empty("com.example", "widget")
                .withVersion("1.0.0")
                .withVersion("2.0.0-SNAPSHOT")
                .render();
        assertThat(MavenMetadata.parse(once).render()).isEqualTo(once);
    }

    @Test
    void a_hand_written_document_without_latest_or_release_reads_and_renders_without_inventing_them() {
        byte[] compact = ("<metadata><groupId>g</groupId><artifactId>a</artifactId>"
                        + "<versioning><versions><version>1.0</version></versions></versioning></metadata>")
                .getBytes(StandardCharsets.UTF_8);
        MavenMetadata md = MavenMetadata.parse(compact);
        assertThat(md.versions()).containsExactly("1.0");
        assertThat(text(md)).doesNotContain("<latest>").doesNotContain("<release>");
    }

    @Test
    void versions_is_an_immutable_snapshot() {
        List<String> versions = MavenMetadata.empty("g", "a").withVersion("1.0").versions();
        assertThat(versions).isUnmodifiable();
    }
}
