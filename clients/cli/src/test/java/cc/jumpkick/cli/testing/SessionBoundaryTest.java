// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The boundary itself, asserted the only way it can be: across two tests in one class. The first
 * leaks deliberately; the second is the one that used to inherit it.
 *
 * <p>Nothing here registers the extension — {@code SessionBoundary} is autodetected for this whole
 * suite, so this also proves the registration is live. Delete-the-mechanism shows up here as a
 * failure in the second test rather than as a flake in some unrelated class three shards away.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionBoundaryTest {

    private static final Path MARKER = Path.of("jk-session-boundary-marker");

    @Test
    @Order(1)
    void a_test_may_install_a_distinctive_session_on_the_static() {
        SessionContext.install(SessionContext.installed().withCacheDir(MARKER));
        assertThat(SessionContext.installed().cacheDir()).isEqualTo(MARKER);
    }

    @Test
    @Order(2)
    void the_next_test_in_the_same_class_does_not_inherit_it() {
        assertThat(SessionContext.installed().cacheDir())
                .describedAs("the previous test's install must not survive into this one")
                .isNotEqualTo(MARKER);
    }
}
