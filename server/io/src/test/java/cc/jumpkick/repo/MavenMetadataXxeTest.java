// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code maven-metadata.xml} arrives from whichever repository the resolver asked, so the version
 * list is untrusted input on every fetch. A DOCTYPE is rejected outright rather than resolved, which
 * is what keeps a hostile mirror from turning a version lookup into a local-file read.
 */
class MavenMetadataXxeTest {

    @Test
    void metadata_with_a_system_entity_does_not_read_the_local_file(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_VALUE");

        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE metadata [ <!ENTITY leak SYSTEM "file://%s"> ]>
                <metadata>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <versioning><versions><version>&leak;</version></versions></versioning>
                </metadata>
                """.formatted(secret.toAbsolutePath());

        assertThatThrownBy(() -> MavenMetadata.parse(xml.getBytes(StandardCharsets.UTF_8)))
                .as("the DOCTYPE is refused outright, not merely the reference to what it declares")
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
