// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The generator plugin's code layer: one generate-stage step per {@code [generate.<name>]} entry,
 * each forking its own tool over its declared inputs and contributing the output to the compiler.
 * The engine fingerprints the declared inputs, the entry's config and the tool closure, and skips
 * the body on a cache hit — no plugin-side staleness logic.
 */
public final class Generators implements Plugin, BuildExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-generator", "##JKGEN:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        ctx.config()
                .entries()
                .forEach((name, values) ->
                        ctx.task(GeneratorEntry.fromConfig(name, values).task()));
    }
}
