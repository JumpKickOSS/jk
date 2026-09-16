// SPDX-License-Identifier: Apache-2.0
package com.example.hello;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPlugin;
import cc.jumpkick.plugin.build.BuildPluginContext;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The code layer of the {@code [hello]} plugin. jk forks the jar as a worker and the SDK's
 * harness answers the protocol; {@link #register} is where a plugin adds steps and packagers,
 * and this one adds none — the manifest's contribution is the whole feature.
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
        // Steps and packagers go here: ctx.task(TaskSpec.named("hello-banner")…).
    }
}
