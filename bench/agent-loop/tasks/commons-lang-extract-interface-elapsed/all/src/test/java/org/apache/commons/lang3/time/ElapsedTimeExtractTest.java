package org.apache.commons.lang3.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Hidden acceptance test for the change task: the interface, what StopWatch implements, and each planted method's parameter. */
class ElapsedTimeExtractTest {

    private static Class<?> elapsedTime() throws ClassNotFoundException {
        return Class.forName("org.apache.commons.lang3.time.ElapsedTime");
    }

    @Test
    void theInterfaceDeclaresExactlyTheThreeMethods() throws ClassNotFoundException {
        final Class<?> type = elapsedTime();
        assertTrue(type.isInterface(), "ElapsedTime is not an interface");
        final Set<String> declared = Arrays.stream(type.getDeclaredMethods()).map(Method::getName).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(Arrays.asList("getDuration", "getNanoTime", "isStarted")), declared);
        assertTrue(type.isAssignableFrom(StopWatch.class), "StopWatch does not implement ElapsedTime");
    }

    @Test
    void theMethodsUsedOnlyThroughItTakeIt() throws ReflectiveOperationException {
        final Class<?> type = elapsedTime();
        final Object stub = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
            case "isStarted":
                return true;
            case "getDuration":
                return Duration.ofMillis(42);
            case "getNanoTime":
                return 5_000L;
            default:
                throw new UnsupportedOperationException(method.getName());
            }
        });
        assertEquals("ran 42 ms", StopWatchLabels.class.getDeclaredMethod("label", type).invoke(null, stub));
        assertEquals(true, StopWatchBudget.class.getDeclaredMethod("overBudget", type, long.class).invoke(null, stub, 4_000L));
        assertEquals(false, StopWatchBudget.class.getDeclaredMethod("overBudget", type, long.class).invoke(null, stub, 6_000L));
    }

    @Test
    void theMethodThatReadsTheMessageKeepsStopWatch() throws NoSuchMethodException {
        StopWatchMessages.class.getDeclaredMethod("line", StopWatch.class);
        assertEquals("load: 0 ms", StopWatchMessages.line(new StopWatch("load")));
    }

}
