// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.TaskContext;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Whether a step is waiting: none of its forks runs and its next one waits for a lease. The step is
 * told ({@link TaskContext#waiting}) each time that changes, so its row can say why. A step with a
 * fork running is working, whatever its other forks wait for.
 */
final class StepWaits {

    /** One step's forks: those running, and each waiting fork's reason. */
    private static final class State {
        int running;
        final Map<Object, WaitReason> waiting = new IdentityHashMap<>();

        @Nullable
        String shown;

        @Nullable
        String state() {
            if (running > 0 || waiting.isEmpty()) return null;
            return waiting.containsValue(WaitReason.MEMORY) ? WaitReason.MEMORY.wire() : WaitReason.SLOT.wire();
        }
    }

    private static final Map<TaskContext, State> STEPS = new IdentityHashMap<>();

    private StepWaits() {}

    /** Fork {@code key} of {@code step} waits for {@code reason}; null when it no longer waits. */
    static void waiting(@Nullable TaskContext step, Object key, @Nullable WaitReason reason) {
        if (step == null) return;
        change(step, s -> {
            if (reason == null) s.waiting.remove(key);
            else s.waiting.put(key, reason);
        });
    }

    /** A fork of {@code step} was granted and runs. */
    static void started(@Nullable TaskContext step) {
        if (step != null) change(step, s -> s.running++);
    }

    /** A running fork of {@code step} released its lease. */
    static void ended(@Nullable TaskContext step) {
        if (step != null) change(step, s -> s.running = Math.max(0, s.running - 1));
    }

    /** Under one lock, so the step hears its states in the order they happened. */
    private static void change(TaskContext step, Consumer<State> edit) {
        synchronized (STEPS) {
            State s = STEPS.computeIfAbsent(step, k -> new State());
            edit.accept(s);
            String next = s.state();
            boolean tell = !Objects.equals(next, s.shown);
            s.shown = next;
            if (s.running == 0 && s.waiting.isEmpty()) STEPS.remove(step);
            if (!tell) return;
            try {
                step.waiting(next);
            } catch (RuntimeException ignored) {
                // a row's state must not fail the fork
            }
        }
    }
}
