// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.host.Hashing;
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
 * A compiler fork that fails is the result of the step. The CAS prewriter's final pass walks and
 * hashes whatever the compiler left in the output tree; on the failure path nothing records that
 * tree, so the pass must not run: its walk can throw over the compiler's own exception, and its
 * copies would leave the CAS holding blobs no record names.
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
                            List.of(out.resolve("A.kt")),
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

    @Test
    void a_compile_that_reports_errors_puts_nothing_it_wrote_into_the_cas(@TempDir Path dir) throws Exception {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, dir.resolve("actions"));
        Path out = Files.createDirectories(dir.resolve("out"));
        byte[] partial = {(byte) 0xCA, (byte) 0xFE, 1, 2, 3};

        LangCompile.Result r = LangCompile.forkAndStore(
                "compile-kotlin",
                "key",
                out,
                List.of(out.resolve("A.kt")),
                true,
                cas,
                cache,
                Map::of,
                snapshot -> List.of(),
                null,
                () -> {
                    try {
                        Files.write(out.resolve("A.class"), partial);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return new CompileResult(false, List.of());
                });

        assertThat(r.success()).isFalse();
        assertThat(cas.contains(Hashing.sha256Hex(partial)))
                .as("a failed compile's output is not copied into the CAS")
                .isFalse();
    }
}
