// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An AAR is third-party content pulled from a repository, so its {@code res/values} XML must never
 * resolve an external entity. The parser rejects a DOCTYPE outright, so a poisoned AAR fails the
 * build rather than quietly folding a local file into the merged resources.
 */
class ResourceMergerXxeTest {

    @Test
    void aar_values_xml_with_a_system_entity_does_not_read_the_local_file(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_VALUE");

        Path container = Files.createDirectories(tmp.resolve("aar"));
        Path values = Files.createDirectories(container.resolve("res").resolve("values"));
        Files.writeString(values.resolve("values.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE resources [ <!ENTITY leak SYSTEM "file://%s"> ]>
                <resources>
                    <string name="app_name">&leak;</string>
                </resources>
                """.formatted(secret.toAbsolutePath()));

        Path out = tmp.resolve("out");
        List<AndroidDeps.Aar> aars = List.of(new AndroidDeps.Aar("poisoned.aar", container));

        assertThatThrownBy(() -> ResourceMerger.mergeDepRes(aars, out))
                .as("a DOCTYPE in third-party AAR resources must be rejected, not resolved")
                .hasMessageContaining("DOCTYPE");

        if (Files.isDirectory(out)) {
            try (var walk = Files.walk(out)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    assertThat(Files.readString(p)).doesNotContain("TOP_SECRET_VALUE");
                }
            }
        }
    }
}
