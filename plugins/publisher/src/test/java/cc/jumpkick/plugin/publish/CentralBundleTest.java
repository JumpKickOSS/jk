// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Project;
import cc.jumpkick.plugin.publish.testkit.GpgTestFixture;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CentralBundleTest {

    @Test
    void the_bundle_is_a_maven_layout_with_a_signature_and_two_checksums_per_artifact(@TempDir Path dir)
            throws Exception {
        var key = GpgTestFixture.generate(dir, "pass");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "pass".toCharArray());
        Project project = new Project("com.example", "widget", "1.0.0", 25);
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);

        CentralBundle.Bundle bundle = CentralBundle.build(
                project,
                List.of(
                        new MavenPublisher.Artifact(".jar", jar),
                        new MavenPublisher.Artifact(".pom", pom),
                        new MavenPublisher.Artifact("-sources.jar", jar),
                        new MavenPublisher.Artifact("-javadoc.jar", jar)),
                signer);

        String stem = "com/example/widget/1.0.0/widget-1.0.0";
        assertThat(bundle.entries())
                .containsExactly(
                        stem + ".jar",
                        stem + ".jar.asc",
                        stem + ".jar.md5",
                        stem + ".jar.sha1",
                        stem + ".pom",
                        stem + ".pom.asc",
                        stem + ".pom.md5",
                        stem + ".pom.sha1",
                        stem + "-sources.jar",
                        stem + "-sources.jar.asc",
                        stem + "-sources.jar.md5",
                        stem + "-sources.jar.sha1",
                        stem + "-javadoc.jar",
                        stem + "-javadoc.jar.asc",
                        stem + "-javadoc.jar.md5",
                        stem + "-javadoc.jar.sha1");

        Map<String, byte[]> zipped = unzip(bundle.zip());
        assertThat(zipped.keySet()).containsExactlyElementsOf(bundle.entries());
        assertThat(entry(zipped, stem + ".jar")).isEqualTo(jar);
        assertThat(new String(entry(zipped, stem + ".jar.md5"), StandardCharsets.US_ASCII))
                .isEqualTo(Checksums.md5Hex(jar));
        assertThat(new String(entry(zipped, stem + ".pom.sha1"), StandardCharsets.US_ASCII))
                .isEqualTo(Checksums.sha1Hex(pom));
        GpgTestFixture.verifyDetached(pom, entry(zipped, stem + ".pom.asc"), key.publicRing());
    }

    static byte[] entry(Map<String, byte[]> zipped, String name) {
        return Objects.requireNonNull(zipped.get(name), name);
    }

    static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }
}
