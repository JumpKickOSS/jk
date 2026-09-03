// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The shared unique-id string walk both sides of the test fork call — the runner's malformed-id
 * fallback and the engine's no-platform-on-classpath parse. One suite covers both consumers.
 */
class JUnitUniqueIdsTest {

    @Test
    void splits_engine_class_method() {
        String id = "[engine:junit-jupiter]/[class:cc.jumpkick.runtime.LockFreshenConservativeTest]"
                + "/[method:freshen_preserves_pins_while_explicit_lock_floats(java.nio.file.Path)]";
        assertEquals("junit-jupiter", JUnitUniqueIds.engineOf(id));
        assertEquals("cc.jumpkick.runtime.LockFreshenConservativeTest", JUnitUniqueIds.classOf(id));
        assertEquals(
                "freshen_preserves_pins_while_explicit_lock_floats(java.nio.file.Path)", JUnitUniqueIds.methodOf(id));
    }

    @Test
    void nested_class_joins_with_dollar() {
        String id = "[engine:junit-jupiter]/[class:com.example.Outer]/[nested-class:Inner]/[method:m()]";
        assertEquals("com.example.Outer$Inner", JUnitUniqueIds.classOf(id));
        assertEquals("m()", JUnitUniqueIds.methodOf(id));
    }

    @Test
    void decodes_percent_encoded_array_params() {
        String id = "[engine:junit-jupiter]/[class:demo.FooTest]/[method:bar(java.lang.String%5B%5D)]";
        assertEquals("demo.FooTest", JUnitUniqueIds.classOf(id));
        assertEquals("bar(java.lang.String[])", JUnitUniqueIds.methodOf(id));
        assertEquals("bar(int[])", JUnitUniqueIds.methodOf("[engine:junit-jupiter]/[class:C]/[method:bar(int%5B%5D)]"));
    }

    @Test
    void decodes_slash_and_percent_in_segment() {
        assertEquals("foo/bar%1", JUnitUniqueIds.methodOf("[engine:junit-jupiter]/[class:C]/[method:foo%2Fbar%251]"));
        assertEquals("plain", JUnitUniqueIds.percentDecode("plain"));
    }

    @Test
    void malformed_escapes_pass_through_intact() {
        assertEquals("bar(int%5", JUnitUniqueIds.percentDecode("bar(int%5"));
        assertEquals("a%zzb", JUnitUniqueIds.percentDecode("a%zzb"));
    }

    @Test
    void malformed_id_yields_empty_fields_not_garbage() {
        assertEquals("", JUnitUniqueIds.classOf("not a unique id"));
        assertEquals("", JUnitUniqueIds.engineOf("not a unique id"));
        assertEquals("", JUnitUniqueIds.methodOf("not a unique id"));
        assertEquals("", JUnitUniqueIds.classOf("[class:Unterminated"));
        assertEquals("", JUnitUniqueIds.classOf(null));
        assertEquals("", JUnitUniqueIds.engineOf(null));
        assertEquals("", JUnitUniqueIds.methodOf(null));
        assertEquals("", JUnitUniqueIds.percentDecode(null));
    }

    @Test
    void dynamic_test_keeps_invocation() {
        assertEquals(
                "dynamicTests()[#1]",
                JUnitUniqueIds.methodOf(
                        "[engine:junit-jupiter]/[class:C]/[test-factory:dynamicTests()]/[dynamic-test:#1]"));
    }

    @Test
    void nested_dynamic_containers_compose_their_indices() {
        // Sibling containers' leaves both end in [dynamic-test:#2]; dropping the
        // container index made them label identically.
        assertEquals(
                "m()[#1/#2]",
                JUnitUniqueIds.methodOf(
                        "[engine:junit-jupiter]/[class:C]/[test-factory:m()]/[dynamic-container:#1]/[dynamic-test:#2]"));
        assertEquals(
                "m()[#3/#2]",
                JUnitUniqueIds.methodOf(
                        "[engine:junit-jupiter]/[class:C]/[test-factory:m()]/[dynamic-container:#3]/[dynamic-test:#2]"));
    }

    @Test
    void parameterized_template_with_array_param() {
        assertEquals(
                "bar(int[])[#1]",
                JUnitUniqueIds.methodOf(
                        "[engine:junit-jupiter]/[class:C]/[test-template:bar(int%5B%5D)]/[test-template-invocation:#1]"));
    }
}
