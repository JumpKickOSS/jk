// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkProgressLabel;
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
        p.onDownloadProgress(51, 200); // still 26%? 50/200=25, 51/200=26...
        p.onDownloadProgress(100, 200);
        p.onDownloadProgress(100, 200);
        p.onExtractStart("Temurin 25");

        assertThat(ctx.labels.getFirst()).isEqualTo(JdkProgressLabel.downloading("Temurin 25", 0, 200));
        assertThat(ctx.labels).contains(JdkProgressLabel.downloading("Temurin 25", 100, 200));
        assertThat(ctx.labels.getLast()).isEqualTo(JdkProgressLabel.installing("Temurin 25"));
        assertThat(ctx.labels.stream().filter(s -> s.endsWith("50%")).count()).isEqualTo(1);
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
        public <T> void put(cc.jumpkick.run.BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(cc.jumpkick.run.BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(cc.jumpkick.run.BuildPlanKey<T> key) {
            throw new IllegalStateException(key.toString());
        }
    }
}
