// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackageContext;
import cc.jumpkick.plugin.build.PackageExtension;
import cc.jumpkick.plugin.build.TestContext;
import cc.jumpkick.plugin.build.TestExtension;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * {@code [intellij]}: an IntelliJ Platform plugin module. The manifest puts the distribution's jars
 * on the classpaths; this code layer adds the step that configures the test JVM as the IDE's own
 * launcher would ({@link TestEnvironment}) and the packager that writes the installable zip
 * ({@link PluginZip}).
 */
public final class IntellijPlugin implements Plugin, TestExtension, PackageExtension {

    /** The test-environment step's name, and {@code jk explain}'s. */
    static final String TEST_ENV_STEP = "intellij-test-env";

    /** The step's output directory; it holds {@link #TEST_JVM_ARGS}. */
    static final String TEST_ENV_DIR = "test-jvm";

    /** One test-JVM flag per line, under the step's output. */
    static final String TEST_JVM_ARGS = TEST_ENV_DIR + "/jvm.args";

    /** One test-classpath jar per line, under the step's output: the bundled plugins the named ones require. */
    static final String TEST_CLASSPATH = TEST_ENV_DIR + "/classpath";

    /** The unpacked distribution, as the manifest names it. */
    static final String IDE = "intellij-ide";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-intellij", "##JKIJ:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void test(TestContext ctx) {
        // One test JVM: the IDE's system and index directories under the sandbox belong to one
        // running application at a time.
        ctx.named(TEST_ENV_STEP)
                .inputs(In.config())
                .outputs(TEST_ENV_DIR)
                .contributesTestJvmArgs(TEST_JVM_ARGS)
                .contributesTestClasspath(TEST_CLASSPATH)
                .oneTestJvm()
                .run(TestEnvironment::run);
    }

    @Override
    public void pack(PackageContext ctx) {
        ctx.inputs(In.classes(), In.runtimeEntries(), In.config()).produce("intellij-plugin", PluginZip::produce);
    }
}
