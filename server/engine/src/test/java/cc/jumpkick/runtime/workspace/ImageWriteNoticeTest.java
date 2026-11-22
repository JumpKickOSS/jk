// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.IoLedger;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RunNotices;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the image worker says reaches the user: an image built without the AOT cache its config asks
 * for (the host cannot run the image's platform) says so on the run, a passing one included, and is
 * not recorded as up to date, so the next build says it again rather than restoring it silently.
 */
class ImageWriteNoticeTest {

    private static final String PINNED_BASE = "eclipse-temurin:25-jre@sha256:" + "a".repeat(64);
    private static final String NO_CACHE =
            "[image] aot-cache: the image has no AOT cache: podman on this host cannot run linux/amd64 programs";

    @Test
    void a_notice_from_the_worker_is_said_and_keeps_the_image_unrecorded(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        AtomicInteger forks = new AtomicInteger();
        List<String> notices = new ArrayList<>();

        assertThat(build(fx, forks, notices, NO_CACHE).success()).isTrue();
        assertThat(notices).singleElement().asString().contains("app write-image: " + NO_CACHE);

        assertThat(build(fx, forks, notices, NO_CACHE).success()).isTrue();
        assertThat(forks.get())
                .as("an image built without what it was asked for is built, and said, again")
                .isEqualTo(2);
        assertThat(notices).hasSize(2);
    }

    @Test
    void an_image_built_as_asked_is_recorded_and_restored(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        AtomicInteger forks = new AtomicInteger();
        List<String> notices = new ArrayList<>();

        assertThat(build(fx, forks, notices, null).success()).isTrue();
        assertThat(build(fx, forks, notices, null).success()).isTrue();

        assertThat(forks.get()).as("the tarball jk wrote is still in place").isEqualTo(1);
        assertThat(notices).isEmpty();
    }

    private record Fixture(JkBuild project, BuildLayout layout, ImageConfig config, Path cache, Path workerJar) {}

    private static Fixture fixture(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
            group = "t"
            name = "app"
            version = "0.1.0"
            jdk = { version = "25" }
            """);
        JkBuild project = JkBuildParser.parse(tmp.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(tmp, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "MAIN");
        Files.createDirectories(layout.ociImageTar().getParent());
        Path workerJar = Files.writeString(tmp.resolve("jk-image-builder.jar"), "WORKER");
        ImageConfig config = new ImageConfig(
                PINNED_BASE,
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true,
                true,
                false);
        return new Fixture(project, layout, config, tmp.resolve("cache"), workerJar);
    }

    /** One build of the image step; the worker writes the tarball and says {@code notice} when given. */
    private static BuildPlanResult build(Fixture fx, AtomicInteger forks, List<String> notices, @Nullable String notice)
            throws Exception {
        Path tarball = fx.layout().ociImageTar();
        Task step = Task.builder("write-image")
                .ticks(1)
                .execute(ctx -> ImageWrite.restoreOrBuild(
                        ctx,
                        fx.project(),
                        fx.layout(),
                        fx.config(),
                        fx.cache(),
                        tarball,
                        new ImagePlans.RuntimeJars(List.of(), List.of(), List.of(), Map.of()),
                        null,
                        "com.example.Main",
                        fx.workerJar(),
                        (base, said) -> {
                            forks.incrementAndGet();
                            try {
                                Files.writeString(tarball, "TARBALL");
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            if (notice != null) {
                                said.accept(PluginReply.diagnostic(PluginProtocol.SEVERITY_NOTICE, null, 0, 0, notice));
                            }
                            return "";
                        }))
                .build();
        BuildPlan plan = BuildPlan.builder("image")
                .stateKeys(ImagePlans.IMAGE_REF)
                .addTask(step)
                .build();
        IoLedger run = new IoLedger();
        RunNotices.openSink(run, (code, message) -> notices.add(message));
        BuildPlanResult[] result = {null};
        try {
            Request.of(1, run).run(() -> result[0] = SessionContext.where(Session.defaults(), plan::run));
        } finally {
            RunNotices.closeSink(run);
        }
        return result[0];
    }
}
