// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.workspace.GraalHomes;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraalHomesTest {

    @Test
    void lookup_matches_absolute_normalize_and_exact_key() {
        Path graal = Path.of("/opt/graalvm");
        Path key = Path.of("/work/mod").toAbsolutePath().normalize();
        Map<Path, Path> map = Map.of(key, graal);
        assertThat(GraalHomes.lookup(key, map)).isEqualTo(graal);
        assertThat(GraalHomes.lookup(Path.of("/work/mod"), map)).isEqualTo(graal);
    }

    @Test
    void lookup_returns_null_when_absent() {
        assertThat(GraalHomes.lookup(Path.of("/other"), Map.of(Path.of("/work/mod"), Path.of("/g"))))
                .isNull();
        assertThat(GraalHomes.lookup(Path.of("/work/mod"), Map.of())).isNull();
    }
}
