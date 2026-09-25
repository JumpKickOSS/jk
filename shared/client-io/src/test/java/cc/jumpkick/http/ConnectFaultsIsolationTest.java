// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The connect-fault memo is cleared between tests in one JVM. Nothing here registers a listener —
 * {@code ConnectFaultsReset} is discovered for the suite, so deleting it fails the second test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConnectFaultsIsolationTest {

    private static final String AUTHORITY = "127.0.0.1:9";

    @Test
    @Order(1)
    void a_test_may_remember_a_dead_address() {
        ConnectFaults.noteRefusing(AUTHORITY, "ConnectException: refused");
        assertThat(ConnectFaults.refusing(AUTHORITY)).startsWith("ConnectException");
    }

    @Test
    @Order(2)
    void the_next_test_in_the_same_jvm_does_not_inherit_it() {
        assertThat(ConnectFaults.refusing(AUTHORITY))
                .as("the previous test's refusal must not survive into this one")
                .isNull();
    }
}
