// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Only jk's own blob pools are content-addressed; a directory called sha256 elsewhere is not. */
class FreshnessStampContentAddressedTest {

    @Test
    void a_checkout_under_a_directory_named_sha256_is_not_a_blob(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        SessionContext.where(Session.defaults().withCacheDir(cache), () -> {
            assertThat(FreshnessStamp.isContentAddressed(tmp.resolve("work/sha256/app/target/classes")))
                    .isFalse();
            assertThat(FreshnessStamp.isContentAddressed(
                            JkStores.cacheCas(cache).root().resolve("ab/abcdef")))
                    .isTrue();
            return null;
        });
    }
}
