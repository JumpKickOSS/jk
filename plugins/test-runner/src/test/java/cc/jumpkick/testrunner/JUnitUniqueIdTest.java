// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class JUnitUniqueIdTest {

    @Test
    void splits_engine_class_method() {
        var id = JUnitUniqueId.parse(
                "[engine:junit-jupiter]/[class:cc.jumpkick.runtime.LockFreshenConservativeTest]"
                        + "/[method:freshen_preserves_pins_while_explicit_lock_floats(java.nio.file.Path)]");
        assertEquals("junit-jupiter", id.testEngine);
        assertEquals("cc.jumpkick.runtime.LockFreshenConservativeTest", id.testClass);
        assertEquals("freshen_preserves_pins_while_explicit_lock_floats(java.nio.file.Path)", id.testMethod);
    }

    @Test
    void nested_class_uses_dollar() {
        var id = JUnitUniqueId.parse(
                "[engine:junit-jupiter]/[class:com.example.Outer]/[nested-class:Inner]/[method:m()]");
        assertEquals("com.example.Outer$Inner", id.testClass);
        assertEquals("m()", id.testMethod);
    }

    @Test
    void putIdentity_omits_display() {
        var id = JUnitUniqueId.parse("[engine:junit-jupiter]/[class:C]/[method:m()]");
        var map = new LinkedHashMap<String, Object>();
        id.putIdentity(map);
        assertEquals(false, map.containsKey("display"));
        assertEquals(false, map.containsKey("id"));
        assertEquals("junit-jupiter", map.get("testEngine"));
        assertEquals("C", map.get("testClass"));
        assertEquals("m()", map.get("testMethod"));
    }
}
