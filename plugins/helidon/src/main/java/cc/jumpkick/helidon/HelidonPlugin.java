// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TestContext;
import cc.jumpkick.plugin.build.TestExtension;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * Helidon plugin. The platform BOM, the annotation processors, the test JVM's config profile and
 * the {@code libs/} layout are the manifest's contributions, which the engine applies without
 * forking this worker's code. The code is two steps, {@code helidon-service-maven-plugin}'s goals
 * run without Maven ({@link HelidonBinding}):
 *
 * <ul>
 *   <li>{@value #BINDING_STEP} ({@code create-application}): after compile, the real application
 *       binding (and {@code ApplicationMain} with {@code generate-main}) compiled over the empty
 *       one the processor wrote; its output replaces the module's classes, so tests, {@code jk
 *       run}, {@code jk dev} and the jar load it. Registered unless {@code binding = false} (and
 *       no {@code generate-main}); keyed by the classes, the runtime classpath, the table and
 *       whether the build is a recompile ({@code jk dev}'s rebuild after a source change keeps the
 *       empty binding).
 *   <li>{@value #TEST_BINDING_STEP} ({@code create-test-application}): with {@code test-binding},
 *       {@code TestApplicationBinding} for the test scope, on the test JVM's classpath.
 * </ul>
 */
public final class HelidonPlugin implements Plugin, BuildExtension, TestExtension {

    static final String BINDING_STEP = "helidon-binding";
    static final String TEST_BINDING_STEP = "helidon-test-binding";

    /** The binding step's output: the module's classes from then on. */
    static final String CLASSES = "classes";

    static final String GENERATED = "generated-sources";

    /** The test binding step's output: a directory on the test JVM's classpath. */
    static final String TEST_CLASSES = "test-classes";

    static final String TEST_GENERATED = "generated-test-sources";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-helidon", "##JKHEL:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        if (!HelidonSettings.of(ctx.config()).bindingStep()) return;
        ctx.named(BINDING_STEP)
                .inputs(In.classes(), In.runtimeClasspath(), In.config(), In.recompile())
                .outputs(CLASSES, GENERATED)
                .transformsClasses(CLASSES)
                .run(HelidonBinding::production);
    }

    @Override
    public void test(TestContext ctx) {
        if (!HelidonSettings.of(ctx.config()).testBinding()) return;
        ctx.named(TEST_BINDING_STEP)
                .inputs(In.testClasses(), In.classes(), In.testRuntimeEntries(), In.config())
                .outputs(TEST_CLASSES, TEST_GENERATED)
                .contributesTestClasspath(TEST_CLASSES)
                .run(HelidonBinding::test);
    }
}
