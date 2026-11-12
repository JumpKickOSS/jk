// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: a member whose {@code [variants]} value declares a published library whose POM
 * depends on a sibling is scheduled after that sibling compiles, from clean, with modules building
 * in parallel.
 */
@Tag("integration")
class WorkspaceVariantPomSubstitutionOrderE2eTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    /** How long core's compile is held at its start, so a consumer admitted early would get there first. */
    private static final long HOLD_MILLIS = 1_500;

    @Test
    void a_sibling_a_variant_values_published_library_depends_on_compiles_before_its_consumer(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("variant-pom-substitution-order-cache");
        // com.foo:middle's published POM depends on the member t:core.
        new MavenStub(http).pom("com.foo", "middle", "1.0", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.foo</groupId><artifactId>middle</artifactId><version>1.0</version>
                  <dependencies><dependency>
                    <groupId>t</groupId><artifactId>core</artifactId><version>1.0.0</version>
                  </dependency></dependencies>
                </project>""");
        Path ws = workspace(tmp, http.baseUrl());
        lock(ws, cache);
        for (int round = 1; round <= 3; round++) {
            // New bytes each round, so core really compiles rather than restoring its tree.
            Files.writeString(ws.resolve("core/src/main/java/t/core/Core.java"), coreSource(round));
            PathUtil.deleteRecursively(ws.resolve("target"));
            for (String module : List.of("core", "app")) PathUtil.deleteRecursively(ws.resolve(module + "/target"));

            Probe probe = new Probe();
            WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                    new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false), probe);
            assertThat(result.errors()).as("round " + round).isEmpty();
            assertThat(result.success())
                    .as("round " + round + "; failed steps " + probe.failed)
                    .isTrue();
            long coreDone = probe.at("core/" + TaskNames.COMPILE_JAVA + "/finish");
            long appStart = probe.at("app/" + TaskNames.RESOLVE_DEPS + "/start");
            assertThat(appStart)
                    .as("round " + round + ": app reads its classpath only once core's classes are whole")
                    .isGreaterThanOrEqualTo(coreDone);
        }
    }

    private static Path workspace(Path tmp, String repo) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "t"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["core", "app"]

                [repositories]
                stub = "%s"
                """.formatted(repo));
        Files.createDirectories(ws.resolve("core/src/main/java/t/core"));
        Files.writeString(ws.resolve("core/jk.toml"), """
                group   = "t"
                name    = "core"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.createDirectories(ws.resolve("app/src/main/java/t/app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "t"
                name    = "app"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [variants.tier]
                default = "paid"
                [variants.tier.free]
                [variants.tier.paid]
                [variants.tier.paid.dependencies]
                middle = { group = "com.foo", name = "middle", version = "1.0" }
                """);
        Files.writeString(ws.resolve("app/src/main/java/t/app/App.java"), """
                package t.app;

                public final class App {
                    private App() {}

                    public static String hello() {
                        return t.core.Core.hello();
                    }
                }
                """);
        return ws;
    }

    private static String coreSource(int round) {
        return """
                package t.core;

                public final class Core {
                    private Core() {}

                    public static String hello() {
                        return "core-%d";
                    }
                }
                """.formatted(round);
    }

    private static void lock(Path ws, Path cache) throws Exception {
        LockPlans.LockScope scope = LockPlans.lockScope(ws);
        BuildPlan lock = LockPlans.lockBuildPlan(
                scope.lockDir(), cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
    }

    /** Records when each module's steps start and finish, and holds core's compile at its start. */
    private static final class Probe implements WorkspaceBuildListener {
        private final Map<String, Long> nanos = new ConcurrentHashMap<>();
        final List<String> failed = new ArrayList<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepStart(String step, @Nullable String group, int ticks) {
                    nanos.putIfAbsent(name + "/" + step + "/start", System.nanoTime());
                    if (name.equals("core") && step.equals(TaskNames.COMPILE_JAVA)) {
                        try {
                            Thread.sleep(HOLD_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    nanos.put(name + "/" + step + "/finish", System.nanoTime());
                    if (status == TaskStatus.FAIL) {
                        synchronized (failed) {
                            failed.add(name + "/" + step);
                        }
                    }
                }
            };
        }

        long at(String event) {
            return Objects.requireNonNull(nanos.get(event), () -> event + " not in " + nanos.keySet());
        }
    }
}
