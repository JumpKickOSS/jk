// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.compat.PublishedChecksum;
import cc.jumpkick.compat.ToolDistribution;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleResolverTest {

    @Test
    void the_default_distribution_is_verified_against_the_sha256_gradle_publishes() {
        ToolDistribution dist = GradleResolver.defaultDistribution();
        assertThat(dist.sha256()).isNull();
        assertThat(dist.tool().publishedChecksums()).containsExactly(PublishedChecksum.SHA256);
        assertThat(PublishedChecksum.SHA256.beside(dist.downloadUri()).toString())
                .startsWith("https://services.gradle.org/distributions/gradle-")
                .endsWith("-bin.zip.sha256");
    }

    @Test
    void a_wrapper_over_https_keeps_its_pin(@TempDir Path project) throws Exception {
        wrapper(
                project,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14-bin.zip\n"
                        + "distributionSha256Sum=" + "b".repeat(64) + "\n");
        ToolDistribution dist = new GradleResolver().resolve(project);
        assertThat(dist.version()).isEqualTo("8.14");
        assertThat(dist.sha256()).isEqualTo("b".repeat(64));
    }

    @Test
    void a_plaintext_wrapper_url_over_the_network_is_refused(@TempDir Path project) throws Exception {
        wrapper(project, "distributionUrl=http\\://mirror.example.com/gradle-8.14-bin.zip\n");
        assertThatThrownBy(() -> new GradleResolver().resolve(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("plaintext http")
                .hasMessageContaining("gradle-wrapper.properties");
    }

    @Test
    void a_plaintext_wrapper_url_on_loopback_is_accepted(@TempDir Path project) throws Exception {
        wrapper(project, "distributionUrl=http\\://localhost:8081/gradle-8.14-bin.zip\n");
        ToolDistribution dist = new GradleResolver().resolve(project);
        assertThat(dist.downloadUri()).isEqualTo(URI.create("http://localhost:8081/gradle-8.14-bin.zip"));
    }

    @Test
    void a_file_wrapper_url_is_accepted(@TempDir Path project) throws Exception {
        wrapper(project, "distributionUrl=file\\:///srv/mirror/gradle-8.14-bin.zip\n");
        ToolDistribution dist = new GradleResolver().resolve(project);
        assertThat(dist.downloadUri()).isEqualTo(URI.create("file:///srv/mirror/gradle-8.14-bin.zip"));
        assertThat(dist.version()).isEqualTo("8.14");
    }

    @Test
    void a_wrapper_url_with_an_unsupported_scheme_is_refused_naming_what_would_work(@TempDir Path project)
            throws Exception {
        wrapper(project, "distributionUrl=ftp\\://mirror.example.com/gradle-8.14-bin.zip\n");
        assertThatThrownBy(() -> new GradleResolver().resolve(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gradle-wrapper.properties")
                .hasMessageContaining("ftp://mirror.example.com/gradle-8.14-bin.zip")
                .hasMessageContaining("https://")
                .hasMessageContaining("loopback")
                .hasMessageContaining("file://");
    }

    private static void wrapper(Path project, String properties) throws IOException {
        Path props = project.resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, properties);
    }
}
