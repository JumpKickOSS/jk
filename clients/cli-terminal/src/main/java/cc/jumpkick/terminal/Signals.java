// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.jspecify.annotations.Nullable;

/**
 * Reflective {@code sun.misc.Signal} wrapper so javac never sees the internal API.
 * Reachability metadata ships with this module.
 */
public final class Signals {
    private Signals() {}

    /**
     * Install {@code handler} for signal {@code name} ({@code "INT"}, {@code "WINCH"}). Returns
     * {@code true} when the handler is registered; {@code false} on the two expected refusals, on
     * which callers keep their best-effort fallback. Any other failure is a bug — a typo'd member
     * after a refactor, missing native-image reachability metadata for the invoke — and throws
     * {@link AssertionError} rather than silently shipping a jk with no Ctrl-C handling.
     */
    public static boolean register(String name, Runnable handler) {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object signal = signalClass.getConstructor(String.class).newInstance(name);
            InvocationHandler ih = (proxy, method, args) -> {
                if ("handle".equals(method.getName())) {
                    handler.run();
                }
                return defaultValue(method);
            };
            Object proxy = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[] {handlerClass}, ih);
            Method handle = signalClass.getMethod("handle", signalClass, handlerClass);
            handle.invoke(null, signal, proxy);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            // A runtime without sun.misc.Signal (or without its native-image metadata).
            return false;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                // A signal this OS/VM refuses: unknown name, WINCH on Windows, -Xrs.
                return false;
            }
            throw new AssertionError("sun.misc.Signal registration failed for " + name, e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new AssertionError("sun.misc.Signal registration failed for " + name, e);
        }
    }

    private static @Nullable Object defaultValue(Method method) {
        Class<?> r = method.getReturnType();
        if (!r.isPrimitive() || r == void.class) {
            return null;
        }
        if (r == boolean.class) {
            return false;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        if (r == short.class) {
            return (short) 0;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == float.class) {
            return 0f;
        }
        if (r == double.class) {
            return 0d;
        }
        if (r == char.class) {
            return '\0';
        }
        return null;
    }
}
