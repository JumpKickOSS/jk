// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.runtime.base.BuildNumberAllocator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildNumberAllocatorTest {

    @Test
    void allocates_monotonic_numbers(@TempDir Path dir) throws Exception {
        Path home = ProjectBuilds.projectHome(dir, "g:proj", Path.of("/proj"));
        Files.createDirectories(home);
        long a = ProjectBuilds.allocateRunNumber(home);
        long b = ProjectBuilds.allocateRunNumber(home);
        Path other = ProjectBuilds.projectHome(dir, "g:other", Path.of("/other"));
        Files.createDirectories(other);
        long c = ProjectBuilds.allocateRunNumber(other);
        assertThat(a).isEqualTo(1);
        assertThat(b).isEqualTo(2);
        assertThat(c).isEqualTo(1);
    }

    @Test
    void allocate_blank_dir_returns_zero() {
        assertThat(BuildNumberAllocator.allocate(null, "g:x")).isEqualTo(0);
        assertThat(BuildNumberAllocator.allocate("  ", "g:x")).isEqualTo(0);
    }
}
