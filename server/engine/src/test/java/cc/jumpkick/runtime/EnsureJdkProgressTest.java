// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkProgressLabel;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EnsureJdkProgressTest {

    @Test
    void labels_download_then_install_and_skips_same_percent() {
        RecordingCtx ctx = new RecordingCtx();
        PlannerSetup.EnsureJdkProgress p = new PlannerSetup.EnsureJdkProgress(ctx);
        p.onDownloadStart("Temurin 25", 200);
        p.onDownloadProgress(50, 200);
        p.onDownloadProgress(51, 200); // floor: 50/200 and 51/200 are both 25% — one label
        p.onDownloadProgress(100, 200);
        p.onDownloadProgress(100, 200);
        p.onExtractStart("Temurin 25");

        assertThat(ctx.labels.getFirst()).isEqualTo(JdkProgressLabel.downloading("Temurin 25", 0, 200));
        assertThat(ctx.labels).contains(JdkProgressLabel.downloading("Temurin 25", 100, 200));
        assertThat(ctx.labels.getLast()).isEqualTo(JdkProgressLabel.installing("Temurin 25"));
        assertThat(ctx.labels.stream().filter(s -> s.endsWith(" 25%")).count()).isEqualTo(1);
        assertThat(ctx.labels.stream().filter(s -> s.endsWith("50%")).count()).isEqualTo(1);
    }

    @Test
    void unknown_size_emits_one_bar_less_label() {
        RecordingCtx ctx = new RecordingCtx();
        PlannerSetup.EnsureJdkProgress p = new PlannerSetup.EnsureJdkProgress(ctx);
        p.onDownloadStart("Temurin 25", 0);
        p.onDownloadProgress(1024, 0);
        p.onDownloadProgress(4096, 0);
        p.onExtractStart("Temurin 25");

        assertThat(ctx.labels).containsExactly("downloading Temurin 25", JdkProgressLabel.installing("Temurin 25"));
    }

    private static final class RecordingCtx implements TaskContext {
        final List<String> labels = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(String description) {
            labels.add(description);
        }

        @Override
        public void output(String line) {}

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
