// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.hibernate;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Hibernate build plugin: {@code [hibernate] enhance = true} rewrites the compiled entity classes
 * with the project's own Hibernate enhancer. The step replaces the module's classes dir, so tests,
 * the jar and {@code jk run} all see the enhanced classes; it is keyed by the classes, the runtime
 * classpath (Hibernate's version among it) and the switches.
 */
public final class HibernatePlugin implements Plugin, BuildExtension {

    static final String ENHANCE_STEP = "hibernate-enhance";
    static final String OUTPUT = "classes";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-hibernate", "##JKHIB:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        if (!ctx.config().bool("enhance", false)) return;
        ctx.named(ENHANCE_STEP)
                .inputs(In.classes(), In.runtimeClasspath(), In.config())
                .outputs(OUTPUT)
                .transformsClasses(OUTPUT)
                .run(HibernatePlugin::enhance);
    }

    static HibernateEnhancer.Switches switches(PluginConfig config) {
        return new HibernateEnhancer.Switches(
                config.bool("lazy-initialization", true),
                config.bool("dirty-tracking", true),
                config.bool("association-management", false),
                config.bool("extended-enhancement", false));
    }

    private static void enhance(TaskExec exec) throws Exception {
        if (!Files.isDirectory(exec.classesDir())) {
            throw new IOException("no compiled classes to enhance at " + exec.classesDir());
        }
        HibernateEnhancer.Result result = HibernateEnhancer.enhance(
                exec.classesDir(), exec.runtimeClasspath(), exec.outputDir(OUTPUT), switches(exec.config()));
        exec.label("Hibernate " + result.hibernateVersion() + " enhanced " + result.enhanced()
                + (result.enhanced() == 1 ? " class" : " classes"));
    }
}
