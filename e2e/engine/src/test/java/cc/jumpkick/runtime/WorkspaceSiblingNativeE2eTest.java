// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.build;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.dirty;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.distributionWorkspace;
import static cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.lock;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.WorkspaceSiblingClassesE2eTest.Probe;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native executable among a source-less distribution's tails, on the workspace {@link
 * WorkspaceSiblingTailsE2eTest} builds: it links lib's jar as the other tails copy it, so it is
 * stale once that jar moves. Each case links an image with every core it can take, which is why it
 * runs in the slow tier; it needs a GraalVM with native-image and is skipped where there is none.
 */
@Tag("slow")
class WorkspaceSiblingNativeE2eTest {

    static final String NATIVE = "\n[native]\nenabled = \"always\"";

    /**
     * A build of lib alone left dist's binary printing lib's previous answer while every later
     * build said OK, and {@code jk explain} priced no native-image for a source-less member at all.
     */
    @Test
    void a_build_of_the_sibling_alone_does_not_leave_the_distributions_native_image_stale(@TempDir Path tmp)
            throws Exception {
        withGraal(() -> {
            WorkspaceSiblingTailsE2eTest.buildSiblingAloneThenWhole("native", tmp);
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
            WorkspaceResult built = build(ws, cache, first);
            assertThat(built.success())
                    .as("initial build; failed steps " + first.failed() + "; errors " + built.errors())
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

    /** {@code body} under a session naming this host's GraalVM; skipped where there is none. */
    private static void withGraal(Callable<Void> body) throws Exception {
        Path graal = NativeTestGraal.home();
        Assumptions.assumeTrue(graal != null, "no GraalVM with native-image on this host");
        Session session = SessionContext.current().withToolchainSpecs(null, null, graal, null);
        SessionContext.where(session, body);
    }
}
