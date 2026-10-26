package org.apache.commons.lang3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: CharSequenceUtils.indexOf is renamed find, every caller follows. */
class CharSequenceUtilsRenameAcceptanceTest {

    @Test
    void indexOfIsGone() {
        for (Method m : CharSequenceUtils.class.getDeclaredMethods()) {
            assertNotEquals("indexOf", m.getName(), "CharSequenceUtils still declares indexOf");
        }
    }

    @Test
    void findDoesWhatIndexOfDid() {
        assertEquals(2, CharSequenceUtils.find("abcd", "cd", 0));
        assertEquals(-1, CharSequenceUtils.find("abcd", "x", 0));
        assertEquals(1, CharSequenceUtils.find("abc", 'b', 0));
        assertEquals(4, CharSequenceUtils.find(new StringBuilder("abcab"), 'b', 2));
    }
}
