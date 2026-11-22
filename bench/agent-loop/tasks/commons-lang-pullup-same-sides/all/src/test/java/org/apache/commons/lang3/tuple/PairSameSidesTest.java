package org.apache.commons.lang3.tuple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: sameSides is declared in Pair alone, works on a MutablePair, and the triple's own stays. */
class PairSameSidesTest {

    private static boolean declares(final Class<?> type, final String name) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).anyMatch(name::equals);
    }

    @Test
    void pairDeclaresItAndImmutablePairDoesNot() throws NoSuchMethodException {
        assertEquals(boolean.class, Pair.class.getDeclaredMethod("sameSides").getReturnType());
        assertFalse(declares(ImmutablePair.class, "sameSides"), "ImmutablePair still declares sameSides");
    }

    @Test
    void theTriplesOwnStaysWhereItIs() {
        assertTrue(declares(ImmutableTriple.class, "sameSides"), "ImmutableTriple.sameSides moved, but the task was the pair's");
        assertFalse(declares(Triple.class, "sameSides"), "Triple declares sameSides, but the task was the pair's");
        assertTrue(ImmutableTriple.of("a", "a", "a").sameSides());
        assertFalse(ImmutableTriple.of("a", "a", "b").sameSides());
    }

    @Test
    void everyPairHasItAndTheCallersStillWork() {
        assertTrue(MutablePair.of("a", "a").sameSides());
        assertFalse(MutablePair.of("a", "b").sameSides());
        assertTrue(ImmutablePair.of(null, null).sameSides());
        assertTrue(PairChecks.allSame(Arrays.asList(ImmutablePair.of("x", "x"), ImmutablePair.of("y", "y"))));
        assertEquals(1, PairChecks.countSame(Arrays.asList(ImmutablePair.of("x", "x"), ImmutablePair.of("x", "y"))));
    }

}
