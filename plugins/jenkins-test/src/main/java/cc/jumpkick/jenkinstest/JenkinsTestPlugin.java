// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jenkinstest;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * {@code [jenkins-test]}: one step that installs the Jenkins plugins of the module's test closure
 * where JenkinsRule's plugin manager loads them, as Maven's {@code maven-hpi-plugin}
 * {@code resolve-test-dependencies} goal does — {@link TestPluginsStep}.
 */
public final class JenkinsTestPlugin implements Plugin, BuildExtension {

    /** The step's name, and {@code jk explain}'s. */
    static final String STEP = "jenkins-test-plugins";

    /** The step's output: a directory on the test classpath holding {@code test-dependencies/}. */
    static final String OUT = "cp";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-jenkins-test", "##JKJT:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        ctx.task(TaskSpec.named(STEP)
                .inputs(In.testRuntimeEntries(), In.repositories(), In.config())
                .outputs(OUT)
                .contributesTestClasspath(OUT)
                .run(TestPluginsStep::run));
    }
}
