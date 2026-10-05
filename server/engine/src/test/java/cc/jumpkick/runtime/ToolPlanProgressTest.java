// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jdk.JdkProgressLabel;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A node or package-manager install draws the JDK's labels: bar and percentage, then installing. */
class ToolPlanProgressTest {

    @Test
    void a_download_labels_as_a_jdk_download_does_one_label_per_percent() {
        RecordingCtx ctx = new RecordingCtx(false);
        ToolPlanProgress p = new ToolPlanProgress(ctx);
        p.downloading("Node.js 24.21.0", 0, 200);
        p.downloading("Node.js 24.21.0", 50, 200);
        p.downloading("Node.js 24.21.0", 51, 200);
        p.downloading("Node.js 24.21.0", 200, 200);
        p.installing("Node.js 24.21.0");
        p.downloading("pnpm 12.9.1", 0, 100);

        assertThat(ctx.labels)
                .containsExactly(
                        JdkProgressLabel.downloading("Node.js 24.21.0", 0, 200),
                        JdkProgressLabel.downloading("Node.js 24.21.0", 50, 200),
                        JdkProgressLabel.downloading("Node.js 24.21.0", 200, 200),
                        JdkProgressLabel.installing("Node.js 24.21.0"),
                        JdkProgressLabel.downloading("pnpm 12.9.1", 0, 100));
        assertThat(ctx.labels.get(1)).isEqualTo("downloading Node.js 24.21.0 ▰▰▰▱▱▱▱▱▱▱ 25%");
    }

    @Test
    void a_cancelled_step_stops_the_download() {
        ToolPlanProgress p = new ToolPlanProgress(new RecordingCtx(true));
        assertThatThrownBy(() -> p.downloading("Node.js 24.21.0", 10, 200)).isInstanceOf(CancellationException.class);
    }

    private static final class RecordingCtx implements TaskContext {
        final List<String> labels = new ArrayList<>();
        private final boolean cancelled;

        RecordingCtx(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {
            labels.add(description);
        }

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

        @Override
        public boolean cancelled() {
            return cancelled;
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
