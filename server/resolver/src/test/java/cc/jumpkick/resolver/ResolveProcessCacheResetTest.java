// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.repo.GradleModuleMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Process resolve memos are cleared between tests in one JVM. Nothing here clears them —
 * {@code ResolveProcessCacheReset} is discovered for the suite, so deleting it fails the second test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResolveProcessCacheResetTest {

    @Test
    @Order(1)
    void a_test_may_fill_the_parse_memo(@TempDir Path dir) throws Exception {
        Path module = dir.resolve("a.module");
        Files.writeString(module, "{}\n");
        GradleModuleMetadata parsed = GradleModuleMetadata.parse(module);
        assertThat(GradleModuleMetadata.parse(module)).isSameAs(parsed);
    }

    @Test
    @Order(2)
    void the_next_test_starts_with_the_memo_empty() {
        assertThat(GradleModuleMetadata.dropParseMemo())
                .as("the previous test's parse must not survive into this one")
                .isZero();
    }
}
