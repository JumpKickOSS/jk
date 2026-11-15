// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.build;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.dirty;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.distributionWorkspace;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.explainDirty;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.forecast;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.lock;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.module;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.nestedLibClass;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.step;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.Probe;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A source-less distribution's packaging tails over its sibling lib, on the workspace {@link
 * WorkspaceSiblingClassesE2eTest} builds: every tail whose inputs are lib's jar (the fat jar, the
 * minified jar, the lib-dir layout, the war, the runtime image, the native executable) is stale
 * once that jar moves, and a deleted tail output is a missing one. The native cases need a GraalVM
 * with native-image and are skipped where there is none.
 */
@Tag("integration")
class WorkspaceSiblingTailsE2eTest {

    private static final String NATIVE = "\n[native]\nenabled = \"always\"";

    /**
     * A build of the sibling alone moves its jar while the distribution over it stays out of the
     * run; the next whole build has to repackage the distribution, not report it up to date over
     * the old bytes. Found end to end: {@code jk build -m lib} then {@code jk build} left dist's fat
     * jar holding lib's previous classes, and every build after it said OK. The same holds for every
     * tail whose inputs are its siblings' jars: the fat jar, and the copy the lib-dir layout, the war
     * and the runtime image each carry.
     */
    @ParameterizedTest
    @ValueSource(strings = {"assembly", "lib-dir", "war", "jlink", "minified"})
    void a_build_of_the_sibling_alone_does_not_leave_the_distribution_stale(String tail, @TempDir Path tmp)
            throws Exception {
        buildSiblingAloneThenWhole(tail, tmp);
    }

    /**
     * The native executable links lib's jar as the other tails copy it: a build of lib alone left
     * dist's binary printing lib's previous answer while every later build said OK, and {@code jk
     * explain} priced no native-image for a source-less member at all.
     */
    @Test
    void a_build_of_the_sibling_alone_does_not_leave_the_distributions_native_image_stale(@TempDir Path tmp)
            throws Exception {
        withGraal(() -> {
            buildSiblingAloneThenWhole("native", tmp);
            return null;
        });
    }

    /**
     * The net under the schedule: a jar that holds other bytes than the image was linked from
     * schedules the distribution even where the preflight vouches for it.
     */
    @Test
    void a_sibling_jar_that_moved_since_the_native_image_was_linked_schedules_it(@TempDir Path tmp) throws Exception {
        withGraal(() -> {
            Path cache = TestCaches.dir("sibling-classes-cache");
            Path ws = distributionWorkspace(tmp, NATIVE);
            SecondBuild.nonce(ws);
            lock(ws, cache, "lib", "dist");
            BuildLayout lib = BuildLayout.of(ws, ws.resolve("lib"), JkBuildParser.parse(ws.resolve("lib/jk.toml")));
            Probe first = new Probe(false);
            assertThat(build(ws, cache, first).success())
                    .as("initial build; failed steps " + first.failed())
                    .isTrue();
            Path distDir = ws.resolve("dist").toRealPath();
            assertThat(dirty(ws, cache)).as("settled after the build").doesNotContain(distDir);

            byte[] jar = Files.readAllBytes(lib.mainJar());
            Files.write(lib.mainJar(), Arrays.copyOf(jar, jar.length + 1));

            assertThat(dirty(ws, cache))
                    .as("dist's native image was linked from the jar lib held before")
                    .contains(distDir);
            return null;
        });
    }

    private static void buildSiblingAloneThenWhole(String tail, Path tmp) throws Exception {
        Path cache = TestCaches.dir("sibling-classes-cache");
        Path ws = distributionWorkspace(
                tmp,
                switch (tail) {
                    case "war" -> "\n[war]";
                    case "jlink" -> "\n[jlink]\naot-cache = false\ntest-start = false";
                    case "native" -> NATIVE;
                    case "minified" -> "minified = true\n\n[minified]";
                    default -> tail + " = true";
                });
        SecondBuild.nonce(ws);
        lock(ws, cache, "lib", "dist");

        Probe first = new Probe(false);
        assertThat(build(ws, cache, first).success())
                .as("initial build; failed steps " + first.failed())
                .isTrue();
        assertThat(distLibClass(ws, tail)).contains("lib-v1");

        Path libSource = ws.resolve("lib/src/com/example/Lib.java");
        Files.writeString(libSource, Files.readString(libSource).replace("lib-v1", "lib-v2"));
        // Only the fat jar and the native image forecast a step; the other tails ride their module's.
        @Nullable
        String tailStep =
                switch (tail) {
                    case "assembly" -> TaskNames.PACKAGE_ASSEMBLY;
                    case "native" -> TaskNames.NATIVE_IMAGE;
                    default -> null;
                };
        if (tailStep != null) {
            List<TaskForecast.Module> edited = forecast(ws, cache);
            assertThat(step(edited, "dist", tailStep).cached())
                    .as("the edit reaches dist's " + tail + ": "
                            + module(edited, "dist").steps())
                    .isFalse();
        }
        // As the build verb sends jk build -m lib: the selection as the dirty hint, and its tokens.
        Set<Path> libOnlyHint = Set.of(BuildGraph.canonicalPath(ws.resolve("lib")));
        Probe libOnly = new Probe(false);
        assertThat(WorkspaceExecute.buildWorkspace(
                                new WorkspaceRequest(
                                                ws, cache, null, 0, null, true, false, 2, libOnlyHint, false, false)
                                        .withModules(List.of("lib")),
                                libOnly)
                        .success())
                .as("jk build -m lib; failed steps " + libOnly.failed())
                .isTrue();
        assertThat(distLibClass(ws, tail)).as("dist was not in that run").contains("lib-v1");

        Path distDir = ws.resolve("dist").toRealPath();
        assertThat(dirty(ws, cache)).as("the build's own check schedules dist").contains(distDir);
        assertThat(explainDirty(ws, cache))
                .as("jk explain predicts dist's work")
                .contains("dist");

        Probe whole = new Probe(false);
        assertThat(build(ws, cache, whole).success())
                .as("jk build; failed steps " + whole.failed())
                .isTrue();
        assertThat(distLibClass(ws, tail))
                .as("dist's " + tail + " carries lib's new bodies")
                .contains("lib-v2")
                .doesNotContain("lib-v1");
        SecondBuild.assertSecondBuildRunsNothing(ws, cache);
        assertThat(distLibClass(ws, tail)).contains("lib-v2");

        // A deleted tail output is a missing output of dist, which the next build brings back.
        deleteTailOutput(ws, tail);
        // Its inputs are unchanged, so the preflight schedules dist to restore the output, not as
        // dirty (the record of its outputs names the same inputs under every verb).
        assertThat(dirty(ws, cache)).as("dist's inputs are unchanged").doesNotContain(distDir);
        assertThat(ModuleOutputs.packageOutputsMissing(ws, distDir, JkBuildParser.parse(distDir.resolve("jk.toml"))))
                .as("dist's " + tail + " is gone: a restore")
                .isTrue();
        assertThat(module(forecast(ws, cache), "dist").dirty())
                .as("jk explain restores dist's " + tail)
                .isTrue();
        Probe restore = new Probe(false);
        assertThat(build(ws, cache, restore).success())
                .as("rebuild after deleting the " + tail + "; failed steps " + restore.failed())
                .isTrue();
        assertThat(distLibClass(ws, tail)).contains("lib-v2");
    }

    private static void deleteTailOutput(Path ws, String tail) throws IOException {
        JkBuild project = JkBuildParser.parse(ws.resolve("dist/jk.toml"));
        BuildLayout dist = BuildLayout.of(ws, ws.resolve("dist"), project);
        switch (tail) {
            case "assembly" -> Files.delete(dist.assemblyJar());
            case "native" -> Files.delete(dist.nativeBinary());
            case "minified" -> Files.delete(dist.minifiedJar());
            case "jlink" -> PathUtil.deleteRecursivelyOrThrow(dist.jlinkImage());
            case "war" -> {
                BuildBlock.War war = Objects.requireNonNull(project.build().war());
                PathUtil.deleteRecursivelyOrThrow(dist.explodedWarDir(war));
                Files.deleteIfExists(dist.warFile(war));
            }
            default -> {
                PathUtil.deleteRecursivelyOrThrow(dist.libDir(Objects.requireNonNull(project.libDir())));
                Files.delete(dist.libDirJar());
            }
        }
    }

    /** lib's class as dist's {@code tail} ships it, or what dist's native executable prints. */
    private static String distLibClass(Path ws, String tail) throws IOException {
        JkBuild project = JkBuildParser.parse(ws.resolve("dist/jk.toml"));
        BuildLayout dist = BuildLayout.of(ws, ws.resolve("dist"), project);
        if (tail.equals("assembly")) return nestedLibClass(dist.assemblyJar());
        if (tail.equals("native")) return NativeTestGraal.run(dist.nativeBinary());
        if (tail.equals("minified")) return nestedLibClass(dist.minifiedJar());
        Path libs =
                switch (tail) {
                    case "war" ->
                        dist.explodedWarDir(
                                        Objects.requireNonNull(project.build().war()))
                                .resolve("WEB-INF/lib");
                    case "jlink" -> dist.jlinkImage().resolve("app/libs");
                    default -> dist.libDir(Objects.requireNonNull(project.libDir()));
                };
        try (var jars = Files.list(libs)) {
            Path jar = jars.filter(j -> j.getFileName().toString().startsWith("lib-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no copy of lib's jar in " + libs));
            return nestedLibClass(jar);
        }
    }

    /** {@code body} under a session naming this host's GraalVM; skipped where there is none. */
    private static void withGraal(Callable<Void> body) throws Exception {
        Path graal = NativeTestGraal.home();
        Assumptions.assumeTrue(graal != null, "no GraalVM with native-image on this host");
        Session session = SessionContext.current().withToolchainSpecs(null, null, graal, null);
        SessionContext.where(session, body);
    }
}
