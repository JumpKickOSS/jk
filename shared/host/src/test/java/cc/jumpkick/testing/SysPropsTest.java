// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The one fixture whose behaviour no consumer asserts, so it gets its own test.
 *
 * <p>{@link Await}, {@link ShortTempDirs}, {@link LoopbackHttp} and {@code FakeBuildIo} are proved
 * by their consumers: break one and thirty suites in five modules go red. {@code SysProps} is not
 * like that — it closes a <em>leak</em>, and a leak has no assertion attached to it. Deleting the
 * restore would leave every consumer green and quietly reinstate the defect (nineteen classes
 * setting a property and restoring nothing). So the restore is asserted here, from inside, by
 * observing across ordered methods what the previous method left behind.
 */
@ExtendWith(SysProps.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SysPropsTest {

    private static final String ADDED = "jk.test.sysprops.added";
    private static final String PRE_EXISTING = "jk.test.sysprops.pre-existing";

    @BeforeAll
    static void setAClassScopedValue() {
        System.setProperty(PRE_EXISTING, "from-before-all");
    }

    @Test
    @Order(1)
    void a_test_may_add_and_overwrite_properties() {
        System.setProperty(ADDED, "set-by-method-one");
        System.setProperty(PRE_EXISTING, "overwritten-by-method-one");

        assertThat(System.getProperty(ADDED)).isEqualTo("set-by-method-one");
        assertThat(System.getProperty(PRE_EXISTING)).isEqualTo("overwritten-by-method-one");
    }

    @Test
    @Order(2)
    void the_next_test_inherits_neither_the_addition_nor_the_overwrite() {
        assertThat(System.getProperty(ADDED))
                .as("a property added by an earlier test must not survive it — the leak SysProps closes")
                .isNull();
        assertThat(System.getProperty(PRE_EXISTING))
                .as("an overwritten property goes back to the value the class established, not the test's")
                .isEqualTo("from-before-all");
    }

    /** The redirect half: a named property pointed at a directory that exists and is this class's. */
    @Nested
    @ExtendWith(SysProps.class)
    @SysProps.TempRoots({"jk.test.sysprops.root.a", "jk.test.sysprops.root.b"})
    class TempRootsRedirect {

        @Test
        void every_named_property_points_at_its_own_fresh_empty_directory() throws Exception {
            Path a = Path.of(System.getProperty("jk.test.sysprops.root.a"));
            Path b = Path.of(System.getProperty("jk.test.sysprops.root.b"));

            assertThat(a).isDirectory().isEmptyDirectory();
            assertThat(b).isDirectory().isEmptyDirectory();
            assertThat(a).isNotEqualTo(b);
            // Writable, because every consumer of this uses it as an artifact store.
            assertThat(Files.writeString(a.resolve("probe"), "x")).exists();
        }
    }
}
