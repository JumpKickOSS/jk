// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * Helidon plugin. It declares no steps: the platform BOM, the annotation processors, the test
 * JVM's config profile and the {@code libs/} layout are the manifest's contributions, which the
 * engine applies without forking this worker's code. {@link HelidonSettings} is the table as the
 * binding step reads it.
 */
public final class HelidonPlugin implements Plugin, BuildExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-helidon", "##JKHEL:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {}
}
