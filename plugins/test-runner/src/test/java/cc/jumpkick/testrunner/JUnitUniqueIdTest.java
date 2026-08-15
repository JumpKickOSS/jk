// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class JUnitUniqueIdTest {

    @Test
    void splits_engine_class_method() {
        var id = JUnitUniqueId.parse("[engine:junit-jupiter]/[class:cc.jumpkick.runtime.LockFreshenConservativeTest]"
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
    void decodes_percent_encoded_array_params() {
        var id =
                JUnitUniqueId.parse("[engine:junit-jupiter]/[class:demo.FooTest]/[method:bar(java.lang.String%5B%5D)]");
        assertEquals("demo.FooTest", id.testClass);
        assertEquals("bar(java.lang.String[])", id.testMethod);

        var ints = JUnitUniqueId.parse("[engine:junit-jupiter]/[class:C]/[method:bar(int%5B%5D)]");
        assertEquals("bar(int[])", ints.testMethod);
    }

    @Test
    void decodes_slash_and_percent_in_segment() {
        var id = JUnitUniqueId.parse("[engine:junit-jupiter]/[class:C]/[method:foo%2Fbar%251]");
        assertEquals("foo/bar%1", id.testMethod);
    }

    @Test
    void dynamic_test_keeps_invocation() {
        var id =
                JUnitUniqueId.parse("[engine:junit-jupiter]/[class:C]/[test-factory:dynamicTests()]/[dynamic-test:#1]");
        assertEquals("dynamicTests()[#1]", id.testMethod);
    }

    @Test
    void nested_dynamic_containers_compose_their_indices() {
        // Sibling containers' leaves both end in [dynamic-test:#2]; dropping the
        // container index made them label identically.
        var a = JUnitUniqueId.parse(
                "[engine:junit-jupiter]/[class:C]/[test-factory:m()]" + "/[dynamic-container:#1]/[dynamic-test:#2]");
        var b = JUnitUniqueId.parse(
                "[engine:junit-jupiter]/[class:C]/[test-factory:m()]" + "/[dynamic-container:#3]/[dynamic-test:#2]");
        assertEquals("m()[#1/#2]", a.testMethod);
        assertEquals("m()[#3/#2]", b.testMethod);
    }

    @Test
    void parameterized_template_with_array_param() {
        var id = JUnitUniqueId.parse(
                "[engine:junit-jupiter]/[class:C]/[test-template:bar(int%5B%5D)]" + "/[test-template-invocation:#1]");
        assertEquals("bar(int[])[#1]", id.testMethod);
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
