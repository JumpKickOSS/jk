// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.javafx;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * JavaFX plugin. It declares no steps: the module path is the manifest's {@code
 * [contribute.jvm-modules]}, which the engine applies to the test JVM and {@code jk run}.
 */
public final class JavafxPlugin implements Plugin, BuildExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-javafx", "##JKFX:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {}
}
