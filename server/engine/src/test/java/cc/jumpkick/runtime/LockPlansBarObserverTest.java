// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The resolve step's bar observer: per-package labels exist only where a formatter was given (the
 * in-process plan), never on the hosted path, whose client draws packages from {@code lock-package}
 * events; the phase lines and the ticks reach the step either way.
 */
class LockPlansBarObserverTest {

    @Test
    void the_hosted_path_ticks_per_graph_package_without_a_label() {
        RecordingContext ctx = new RecordingContext();
        ResolveObserver bar = LockPlans.barObserver(ctx, ResolveObserver.NOOP, new AtomicInteger(0), null);

        bar.onGraphPackage("com.foo:bar", "1.0");
        bar.onPackage("com.foo:bar", "1.0");

        assertThat(ctx.labels).isEmpty();
        assertThat(ctx.progress.get()).isEqualTo(2);
    }

    @Test
    void the_phase_line_reaches_the_step_on_the_hosted_path() {
        RecordingContext ctx = new RecordingContext();
        ResolveObserver bar = LockPlans.barObserver(ctx, ResolveObserver.NOOP, new AtomicInteger(0), null);

        bar.onPhase("Resolving dependency graph… 12 packages so far, 3s");

        assertThat(ctx.labels).containsExactly("Resolving dependency graph… 12 packages so far, 3s");
    }

    @Test
    void the_in_process_path_labels_each_package_through_its_formatter() {
        RecordingContext ctx = new RecordingContext();
        ResolveObserver bar = LockPlans.barObserver(
                ctx, ResolveObserver.NOOP, new AtomicInteger(0), (module, version) -> module + "@" + version);

        bar.onGraphPackage("com.foo:bar", "1.0");
        bar.onPackage("com.foo:bar", "1.0");

        assertThat(ctx.labels).containsExactly("Resolving com.foo:bar@1.0", "Fetched com.foo:bar@1.0");
    }

    private static final class RecordingContext implements TaskContext {
        final List<String> labels = new ArrayList<>();
        final AtomicInteger progress = new AtomicInteger();

        @Override
        public void progress(int delta) {
            progress.addAndGet(delta);
        }

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {
            labels.add(String.valueOf(description));
        }

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException(key.toString());
        }
    }
}
