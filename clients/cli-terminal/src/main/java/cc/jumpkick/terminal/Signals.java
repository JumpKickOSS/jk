// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Reflective {@code sun.misc.Signal} wrapper so javac never sees the internal API.
 * Reachability metadata ships with this module.
 */
public final class Signals {
    private Signals() {}

    public static void register(String name, Runnable handler) {
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
        } catch (Throwable ignored) {
            // unsupported runtime — caller keeps plan-start-only / no-op behavior
        }
    }

    private static Object defaultValue(Method method) {
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
