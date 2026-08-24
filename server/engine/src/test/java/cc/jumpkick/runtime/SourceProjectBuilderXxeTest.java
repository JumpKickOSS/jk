// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A path or git dependency's {@code pom.xml} is third-party content, so reading its coordinate must
 * never resolve an external entity. The parser rejects a DOCTYPE outright, so a poisoned POM fails
 * the build rather than smuggling a local file into a coordinate — and thence into a lockfile.
 */
class SourceProjectBuilderXxeTest {

    @Test
    void pom_with_a_system_entity_does_not_read_the_local_file(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_VALUE");

        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE project [ <!ENTITY leak SYSTEM "file://%s"> ]>
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>&leak;</artifactId>
                    <version>1.0.0</version>
                </project>
                """.formatted(secret.toAbsolutePath()));

        assertThatThrownBy(() -> SourceProjectBuilder.parseMavenGav(pom))
                .isInstanceOf(IOException.class)
                .as("a DOCTYPE in a third-party pom.xml must be rejected, not resolved")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TOP_SECRET_VALUE"));
    }

    @Test
    void a_plain_pom_still_parses(@TempDir Path tmp) throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="utf-8"?>
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>widget</artifactId>
                    <version>2.1.0</version>
                </project>
                """);

        var gav = SourceProjectBuilder.parseMavenGav(pom);
        assertThat(gav.group()).isEqualTo("com.example");
        assertThat(gav.artifact()).isEqualTo("widget");
        assertThat(gav.version()).isEqualTo("2.1.0");
    }
}
