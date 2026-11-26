// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.ReleasePlatform;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [publish] release} names where deliverables go; {@code [release] platforms} which platforms a release carries. */
class ReleaseConfigTest {

    @TempDir
    Path tmp;

    private Path manifest(String toml) throws Exception {
        return Files.writeString(
                tmp.resolve("jk.toml"), "name = \"app\"\ngroup = \"com.example\"\nversion = \"1.4.7\"\n" + toml);
    }

    @Test
    void a_bucket_url_is_a_prefix_that_ends_in_a_slash() {
        ReleaseTarget.Bucket bucket = (ReleaseTarget.Bucket) ReleaseTarget.parse("s3://acme-releases/app");
        assertThat(bucket.base()).isEqualTo(URI.create("s3://acme-releases/app/"));
        assertThat(bucket.file("1.4.7", "app-1.4.7-all.jar"))
                .isEqualTo(URI.create("s3://acme-releases/app/1.4.7/app-1.4.7-all.jar"));
        assertThat(bucket.latest()).isEqualTo(URI.create("s3://acme-releases/app/latest.json"));
        assertThat(((ReleaseTarget.Bucket) ReleaseTarget.parse("gs://acme")).version("2.0"))
                .isEqualTo(URI.create("gs://acme/2.0/"));
    }

    @Test
    void the_forges_parse_and_anything_else_is_refused_with_the_choices() {
        assertThat(ReleaseTarget.parse("github")).isEqualTo(new ReleaseTarget.Forge("github"));
        assertThat(ReleaseTarget.refusal("https://example.com/releases"))
                .isEqualTo("`https://example.com/releases` is not github, gitlab, forgejo or an s3:// or gs:// URL");
        assertThat(ReleaseTarget.refusal("s3:///prefix")).contains("names no bucket");
        assertThat(ReleaseTarget.refusal("s3://bucket/?x=1")).contains("query or fragment");
    }

    @Test
    void the_publish_table_carries_the_release_target() throws Exception {
        Path m = manifest("""
                [publish]
                release = "s3://acme-releases/app/"
                """);
        assertThat(JkBuildParser.parse(m).pomMetadata().release()).isEqualTo("s3://acme-releases/app/");
        assertThatThrownBy(() -> JkBuildParser.parse(manifest("""
                        [publish]
                        release = "ftp://example.com/"
                        """)))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[publish] release: `ftp://example.com/` is not github");
    }

    @Test
    void platforms_default_to_the_host_and_are_checked_against_the_known_names() throws Exception {
        assertThat(JkBuildParser.releaseTable(manifest("")).platformsOrHost())
                .containsExactly(ReleasePlatform.current());
        Path listed = manifest("""
                [release]
                platforms = ["linux-x86_64", "macos-aarch64", "windows-x86_64"]
                """);
        assertThat(JkBuildParser.releaseTable(listed).platforms())
                .containsExactly("linux-x86_64", "macos-aarch64", "windows-x86_64");
        assertThatThrownBy(() -> JkBuildParser.releaseTable(manifest("""
                        [release]
                        platforms = ["linux-amd64"]
                        """)))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`linux-amd64` is not one of linux-x86_64");
        assertThatThrownBy(() -> JkBuildParser.releaseTable(manifest("""
                        [release]
                        platforms = ["linux-x86_64", "linux-x86_64"]
                        """)))
                .hasMessage("[release] platforms: linux-x86_64 twice");
        assertThatThrownBy(() -> JkBuildParser.releaseTable(manifest("""
                        [release]
                        platforms = []
                        """))).hasMessageContaining("must list platforms");
        assertThat(List.copyOf(ReleasePlatform.KNOWN)).contains(ReleasePlatform.current());
    }
}
