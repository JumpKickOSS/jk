package org.apache.commons.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: Util.stripLeadingHyphens is renamed withoutLeadingHyphens. */
class UtilWithoutLeadingHyphensAcceptanceTest {

    @Test
    void stripLeadingHyphensIsGone() {
        assertFalse(Arrays.stream(Util.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch("stripLeadingHyphens"::equals));
    }

    @Test
    void withoutLeadingHyphensStripsOneOrTwo() {
        assertEquals("f", Util.withoutLeadingHyphens("-f"));
        assertEquals("foo", Util.withoutLeadingHyphens("--foo"));
        assertEquals("foo", Util.withoutLeadingHyphens("foo"));
    }
}
