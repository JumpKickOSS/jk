// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [build] cache-heavy-outputs}: a project can keep its heavy outputs out of the cache, nothing more. */
class JkBuildParserCacheHeavyTest {

    private static final String HEAD = "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n";

    @Test
    void heavy_outputs_are_cached_unless_the_project_turns_it_off(@TempDir Path tmp) throws Exception {
        Path plain = Files.writeString(tmp.resolve("jk.toml"), HEAD);
        assertThat(JkBuildParser.parse(plain).build().cacheHeavyOutputs()).isTrue();

        Path off = Files.writeString(tmp.resolve("off.toml"), HEAD + "[build]\ncache-heavy-outputs = false\n");
        assertThat(JkBuildParser.parse(off).build().cacheHeavyOutputs()).isFalse();

        Path bad = Files.writeString(tmp.resolve("bad.toml"), HEAD + "[build]\ncache-heavy-outputs = \"12h\"\n");
        assertThatThrownBy(() -> JkBuildParser.parse(bad))
                .hasMessageContaining("[build].cache-heavy-outputs must be true or false");
    }
}
