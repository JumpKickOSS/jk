// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.lang.reflect.Proxy;

/**
 * Best-effort ignore of terminal-generated signals for the engine process. The engine must not die
 * when the spawning client's process group receives Ctrl-C; build cancel is a wire concern
 * ({@code CANCEL_REQUEST}), never SIGINT. SIGTERM stays lethal so {@code kill <pid>} works.
 *
 * <p>Uses {@code sun.misc.Signal} when present (HotSpot). Failures are ignored — {@link
 * PosixDetach} already reduces exposure by moving into a new session.
 */
public final class TerminalSignals {

    private TerminalSignals() {}

    /** Ignore SIGINT and SIGHUP when the platform supports it. */
    public static void ignoreInterruptAndHangup() {
        ignore("INT");
        ignore("HUP");
    }

    private static void ignore(String name) {
        try {
            // HotSpot: sun.misc.Signal. Not on every runtime; best-effort only.
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object signal = signalClass.getConstructor(String.class).newInstance(name);
            Object handler = Proxy.newProxyInstance(
                    handlerClass.getClassLoader(), new Class<?>[] {handlerClass}, (proxy, method, args) -> null);
            signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No-op: PosixDetach + process-group isolation is the primary defense.
        }
    }
}
