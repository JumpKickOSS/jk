// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.PublishedChecksum;
import cc.jumpkick.compat.ToolDistribution;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenResolverTest {

    @Test
    void a_wrapper_version_that_is_not_one_segment_fails_before_any_install(@TempDir Path dir) throws Exception {
        Path props = dir.resolve("maven-wrapper.properties");
        Files.writeString(props, "distributionUrl=https://example.com/apache-maven-..%5C..%5Coutside-bin.zip\n");
        Path sentinel = dir.resolve("outside");
        Files.writeString(sentinel, "keep");
        assertThatThrownBy(() -> MavenResolver.fromWrapperProperties(props)).isInstanceOf(IOException.class);
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
        assertThatThrownBy(() -> MavenResolver.distributionFor("..")).isInstanceOf(IllegalArgumentException.class);
        assertThat(MavenResolver.distributionFor("3.9.9").version()).isEqualTo("3.9.9");
    }

    @Test
    void the_default_distribution_is_verified_against_the_sha512_central_publishes_then_the_sha1() {
        ToolDistribution dist = MavenResolver.defaultDistribution();
        assertThat(dist.sha256()).isNull();
        assertThat(dist.tool().publishedChecksums()).containsExactly(PublishedChecksum.SHA512, PublishedChecksum.SHA1);
        assertThat(PublishedChecksum.SHA512.beside(dist.downloadUri()).toString())
                .startsWith("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/")
                .endsWith("-bin.zip.sha512");
    }

    @Test
    void without_a_wrapper_the_newest_known_3_9_is_provisioned_unless_the_enforcer_asks_for_more(@TempDir Path dir)
            throws Exception {
        assertThat(MavenResolver.DEFAULT_VERSION).startsWith("3.9.");
        assertThat(new MavenResolver().resolve(dir).version()).isEqualTo(MavenResolver.DEFAULT_VERSION);
        // A floor the default satisfies leaves the default in place.
        assertThat(new MavenResolver()
                        .resolve(EnforcerMavenVersionTest.pom(dir, "[3.9.11,)", ""))
                        .version())
                .isEqualTo(MavenResolver.DEFAULT_VERSION);
        // A floor above it is provisioned as the floor itself.
        ToolDistribution above = new MavenResolver().resolve(EnforcerMavenVersionTest.pom(dir, "3.9.99", ""));
        assertThat(above.version()).isEqualTo("3.9.99");
        assertThat(above.downloadUri().toString()).endsWith("/3.9.99/apache-maven-3.9.99-bin.zip");
    }

    @Test
    void a_wrapper_wins_over_the_enforcer_floor(@TempDir Path dir) throws Exception {
        Path project = EnforcerMavenVersionTest.pom(dir, "3.9.99", "");
        wrapper(
                project,
                "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip\n");
        assertThat(new MavenResolver().resolve(project).version()).isEqualTo("3.9.6");
    }

    @Test
    void a_wrapper_over_https_is_accepted_with_its_pin(@TempDir Path project) throws Exception {
        Path props = wrapper(
                project,
                "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip\n"
                        + "distributionSha256Sum=" + "a".repeat(64) + "\n");
        ToolDistribution dist = new MavenResolver().resolve(project);
        assertThat(dist.tool()).isEqualTo(BuildTool.MAVEN);
        assertThat(dist.version()).isEqualTo("3.9.6");
        assertThat(dist.sha256()).isEqualTo("a".repeat(64));
        assertThat(props).exists();
    }

    @Test
    void a_plaintext_wrapper_url_over_the_network_is_refused(@TempDir Path project) throws Exception {
        wrapper(project, "distributionUrl=http://mirror.example.com/maven/apache-maven-3.9.6-bin.zip\n");
        assertThatThrownBy(() -> new MavenResolver().resolve(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("plaintext http")
                .hasMessageContaining("maven-wrapper.properties")
                .hasMessageContaining("https://");
    }

    @Test
    void a_plaintext_wrapper_url_on_loopback_is_accepted(@TempDir Path project) throws Exception {
        wrapper(project, "distributionUrl=http://127.0.0.1:8081/maven/apache-maven-3.9.6-bin.zip\n");
        ToolDistribution dist = new MavenResolver().resolve(project);
        assertThat(dist.downloadUri()).isEqualTo(URI.create("http://127.0.0.1:8081/maven/apache-maven-3.9.6-bin.zip"));
        assertThat(dist.sha256()).isNull();
    }

    @Test
    void a_file_wrapper_url_is_accepted(@TempDir Path project) throws Exception {
        wrapper(project, "distributionUrl=file:///srv/mirror/maven/apache-maven-3.9.6-bin.zip\n");
        ToolDistribution dist = new MavenResolver().resolve(project);
        assertThat(dist.downloadUri()).isEqualTo(URI.create("file:///srv/mirror/maven/apache-maven-3.9.6-bin.zip"));
        assertThat(dist.version()).isEqualTo("3.9.6");
        assertThat(dist.archiveType()).isEqualTo("zip");
    }

    @Test
    void a_wrapper_url_with_an_unsupported_scheme_is_refused_naming_what_would_work(@TempDir Path project)
            throws Exception {
        Path props = wrapper(project, "distributionUrl=ftp://mirror.example.com/maven/apache-maven-3.9.6-bin.zip\n");
        assertThatThrownBy(() -> new MavenResolver().resolve(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(props.toString())
                .hasMessageContaining("ftp://mirror.example.com/maven/apache-maven-3.9.6-bin.zip")
                .hasMessageContaining("https://")
                .hasMessageContaining("loopback")
                .hasMessageContaining("file://");
    }

    private static Path wrapper(Path project, String properties) throws IOException {
        Path props = project.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(props.getParent());
        return Files.writeString(props, properties);
    }
}
