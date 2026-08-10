// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.minified;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackageContext;
import cc.jumpkick.plugin.build.PackageExtension;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Minified plugin: registers the {@code minified-jar} packager (R8 over classes + runtime closure).
 */
public final class MinifiedPlugin implements Plugin, PackageExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-minified", "##JKMIN:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void pack(PackageContext ctx) {
        List<In> inputs = new ArrayList<>(List.of(In.classes(), In.runtimeEntries(), In.config()));
        for (String rel : ctx.config().stringList("keep-files")) {
            inputs.add(In.projectFiles(rel));
        }
        ctx.inputs(inputs.toArray(new In[0])).produce("minified-jar", MinifiedJarPackager::produce);
    }
}
