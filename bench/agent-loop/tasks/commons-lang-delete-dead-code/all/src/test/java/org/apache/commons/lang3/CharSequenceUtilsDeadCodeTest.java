package org.apache.commons.lang3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: the dead code is gone and the method a method reference uses is kept. */
class CharSequenceUtilsDeadCodeTest {

    @Test
    void theUnusedMethodsAndTypeAreGone() {
        final Set<String> declared = Arrays.stream(CharSequenceUtils.class.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
        assertFalse(declared.contains("isEmpty"), "CharSequenceUtils.isEmpty is still declared");
        assertFalse(declared.contains("legacyQuote"), "CharSequenceUtils.legacyQuote is still declared");
        assertTrue(declared.contains("length"), "CharSequenceUtils.length is gone, but CharSequenceTotals uses it");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.commons.lang3.CharSequenceLegacyQuoting"));
    }

    @Test
    void theMethodTheTotalsReferenceIsKept() {
        assertEquals(5, CharSequenceTotals.total(Arrays.asList("ab", null, "cde")));
        assertEquals(0, CharSequenceTotals.total(List.of()));
    }

}
