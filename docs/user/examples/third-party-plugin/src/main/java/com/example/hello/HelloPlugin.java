// SPDX-License-Identifier: Apache-2.0
package com.example.hello;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPlugin;
import cc.jumpkick.plugin.build.BuildPluginContext;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.PluginCommandSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The code layer of the {@code [hello]} plugin. jk forks the jar as a worker and the SDK's
 * harness answers the protocol; {@link #register} is where a plugin adds steps, packagers and
 * commands. This one adds a {@code jk hello} command that greets with the consumer's
 * {@code [hello].greeting}.
 */
public final class HelloPlugin implements Plugin, BuildPlugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("hello", "##HELLO:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void register(BuildPluginContext ctx) {
        // Steps and packagers go here too: ctx.task(TaskSpec.named("hello-banner")…).
        ctx.command(PluginCommandSpec.named("hello")
                .description("Print the configured greeting")
                .run(exec -> {
                    String who = exec.args().isEmpty() ? "world" : String.join(" ", exec.args());
                    exec.out(exec.config().string("greeting") + ", " + who);
                    return 0;
                }));
    }
}
