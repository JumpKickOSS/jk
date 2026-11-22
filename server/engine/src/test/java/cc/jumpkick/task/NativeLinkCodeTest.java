// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The code a native image's key names as its producer is the driver, its nested classes with it. */
class NativeLinkCodeTest {

    @Test
    void the_set_is_the_driver_and_its_nested_classes() {
        assertThat(NativeLinkCode.contains("cc/jumpkick/tool/NativeImageDriver.class"))
                .isTrue();
        assertThat(NativeLinkCode.contains("cc/jumpkick/tool/NativeImageDriver$Request.class"))
                .isTrue();
        assertThat(NativeLinkCode.contains("cc/jumpkick/tool/NativeImageDriverProbe.class"))
                .as("a class whose name only starts like the driver's")
                .isFalse();
        assertThat(NativeLinkCode.contains("cc/jumpkick/runtime/PlannerNative.class"))
                .as("the engine code that resolves the args is keyed through the args")
                .isFalse();
    }
}
