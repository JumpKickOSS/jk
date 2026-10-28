package org.apache.commons.lang3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: the instance method Range.contains is renamed includes. */
class RangeIncludesAcceptanceTest {

    @Test
    void containsIsGone() {
        for (Method m : Range.class.getDeclaredMethods()) {
            assertNotEquals("contains", m.getName(), "Range still declares contains");
        }
    }

    @Test
    void includesDoesWhatContainsDid() {
        final Range<Integer> range = Range.of(10, 20);
        assertTrue(range.includes(15));
        assertTrue(range.includes(10));
        assertFalse(range.includes(5));
        assertFalse(range.includes(null));
        assertTrue(range.containsRange(Range.of(12, 14)), "containsRange stays as it is");
    }
}
