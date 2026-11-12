// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * Runs each test method as one build step, so what a step keeps for itself ({@link HeapNotes}) is
 * there for the test to read and gone when the test ends.
 */
public final class AsOneStep implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(
            Invocation<@Nullable Void> invocation, ReflectiveInvocationContext<Method> call, ExtensionContext context)
            throws Throwable {
        StepScope.run(step(context.getDisplayName()), invocation::proceed);
    }

    private static TaskContext step(String name) {
        return (TaskContext) Proxy.newProxyInstance(
                TaskContext.class.getClassLoader(),
                new Class<?>[] {TaskContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    case "cancelled" -> false;
                    case "get" -> Optional.empty();
                    default -> null;
                });
    }
}
