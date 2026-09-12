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
    void the_default_distribution_is_verified_against_the_sha512_central_publishes() {
        ToolDistribution dist = MavenResolver.defaultDistribution();
        assertThat(dist.sha256()).isNull();
        assertThat(dist.tool().publishedChecksum()).isEqualTo(PublishedChecksum.SHA512);
        assertThat(dist.tool().publishedChecksum().beside(dist.downloadUri()).toString())
                .startsWith("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/")
                .endsWith("-bin.zip.sha512");
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

    private static Path wrapper(Path project, String properties) throws IOException {
        Path props = project.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(props.getParent());
        return Files.writeString(props, properties);
    }
}
