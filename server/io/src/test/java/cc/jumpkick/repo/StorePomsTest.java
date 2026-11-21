// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.resolve.ResolveProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The resolve profile counts a POM's parse once per read of the file, not once per lookup. */
class StorePomsTest {

    @BeforeEach
    void on() {
        System.setProperty("jk.resolve.profile", "true");
        ResolveProfile.reset();
        StorePoms.dropMemo();
    }

    @AfterEach
    void off() {
        System.clearProperty("jk.resolve.profile");
        ResolveProfile.reset();
        StorePoms.dropMemo();
    }

    @Test
    void a_pom_parsed_once_and_looked_up_twice_is_one_parse_in_the_profile(@TempDir Path tmp) throws Exception {
        Path pom = tmp.resolve("lib-1.0.pom");
        Files.writeString(pom, """
                <project>
                  <groupId>g</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        StorePoms.parse(pom);
        StorePoms.parse(pom);

        assertThat(ResolveProfile.report()).containsPattern(" pomParse=\\d+ms/1 ");
    }
}
