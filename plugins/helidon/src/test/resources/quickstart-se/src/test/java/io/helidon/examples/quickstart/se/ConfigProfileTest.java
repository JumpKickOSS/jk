package io.helidon.examples.quickstart.se;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;

/** Not part of the Helidon quickstart: the test JVM carries the config profile [helidon] defaults. */
class ConfigProfileTest {

    @Test
    void testsRunUnderTheTestProfile() {
        assertThat(System.getProperty("helidon.config.profile"), is("test"));
    }
}
