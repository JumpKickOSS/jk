// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A compiler fork that throws is the result of the step. The CAS prewriter's final pass walks
 * and hashes whatever the dying compiler left in the output tree, and that walk can throw too;
 * it must not run on the failure path, where its exception would replace the compiler's.
 */
class LangCompileForkFailureTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_throwing_fork_propagates_its_own_exception_over_an_unwalkable_output_tree(@TempDir Path dir)
            throws Exception {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, dir.resolve("actions"));
        Path out = Files.createDirectories(dir.resolve("out"));
        Path sealed = Files.createDirectories(out.resolve("x"));
        RuntimeException died = new IllegalStateException("kotlinc died");
        try {
            assertThatThrownBy(() -> LangCompile.forkAndStore(
                            "compile-kotlin",
                            "key",
                            out,
                            false,
                            true,
                            cas,
                            cache,
                            Map::of,
                            snapshot -> List.of(),
                            null,
                            () -> {
                                try {
                                    Files.write(sealed.resolve("A.class"), new byte[] {1});
                                    Files.setPosixFilePermissions(sealed, EnumSet.noneOf(PosixFilePermission.class));
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                                throw died;
                            }))
                    .as("the compiler's failure, not the walk over what it left behind")
                    .isSameAs(died);
        } finally {
            Files.setPosixFilePermissions(sealed, EnumSet.allOf(PosixFilePermission.class));
        }
    }
}
