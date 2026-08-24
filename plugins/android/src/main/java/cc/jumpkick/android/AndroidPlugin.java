// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildPlugin;
import cc.jumpkick.plugin.build.BuildPluginContext;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.PackagerSpec;
import cc.jumpkick.plugin.build.PluginCommandSpec;
import cc.jumpkick.plugin.build.TaskSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Android build plugin: manifest merge, aapt2, d8/R8, and apk/aab packagers over the public SPI.
 * Engine fingerprints declared inputs and skips step bodies on cache hits.
 */
public final class AndroidPlugin implements Plugin, BuildPlugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-android", "##JKAN:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void register(BuildPluginContext ctx) {
        boolean library = ctx.config().bool("library", false);
        // The effective config carries the selected build type (VariantApply injected it);
        // release defaults minify ON (AGP-9 posture) — the overlay's tri-state `minify` overrides.
        boolean release = "release".equals(ctx.config().stringOpt("build-type").orElse("debug"));
        boolean minify = ctx.config().bool("minify").orElse(release && !library);
        // Both manifest/res locations are declared inputs: jk's simple layout at the module
        // root, and the AGP/traditional src/main/ home — the steps read whichever exists.
        ctx.task(TaskSpec.named("android-manifest")
                .inputs(
                        In.projectFiles("AndroidManifest.xml"),
                        In.projectFiles("src/main/AndroidManifest.xml"),
                        In.runtimeEntries(),
                        In.config())
                .outputs("merged")
                .run(ManifestStep::run));
        ctx.task(TaskSpec.named("android-res")
                .inputs(
                        In.projectFiles("res"),
                        In.projectFiles("src/main/res"),
                        In.stepOutput("android-manifest"),
                        In.runtimeEntries(),
                        In.config())
                .outputs("gen", "packaged", "raw-res")
                .contributesSources("gen")
                .run(ResourceStep::run));
        // Robolectric wiring: a test_config.properties dir on the module's
        // test runtime classpath, pointing at the merged manifest + linked resources.
        ctx.task(TaskSpec.named("android-test-config")
                .inputs(In.stepOutput("android-manifest"), In.stepOutput("android-res"), In.config())
                .outputs("cp")
                .contributesTestClasspath("cp")
                .run(TestConfigStep::run));
        if (ctx.config().bool("build-config", false)) {
            ctx.task(TaskSpec.named("android-buildconfig")
                    .inputs(In.config())
                    .outputs("gen")
                    .contributesSources("gen")
                    .run(BuildConfigStep::run));
        }
        if (ctx.config().bool("hilt", false) && !library) {
            // Hilt: rewrite @AndroidEntryPoint/@HiltAndroidApp superclasses to KSP-generated
            // Hilt_* bases (classes-transform SPI). Dex consumes the transformed dir.
            ctx.task(TaskSpec.named("android-hilt-transform")
                    .inputs(In.classes(), In.config())
                    .outputs("classes")
                    .transformsClasses("classes")
                    .run(HiltTransformStep::run));
        }
        if (library) {
            // A library packages an AAR (classes.jar without R classes + raw res + merged
            // manifest + R.txt) and is never dexed or deployed — consumers dex it.
            ctx.packaging(PackagerSpec.replacingMainArtifact("aar")
                    .inputs(In.classes(), In.stepOutput("android-res"), In.stepOutput("android-manifest"), In.config())
                    .produce(AarPackager::produce));
        } else {
            String dexStep;
            if (minify) {
                // Release: R8 full mode, whole-program — keep rules from the plugin baseline,
                // aapt2's generated rules, AAR consumer rules, and the app's proguard-files
                // (declared inputs, so a rules edit re-shrinks).
                dexStep = "android-r8";
                List<In> r8Inputs = new ArrayList<>(
                        List.of(In.classes(), In.runtimeEntries(), In.stepOutput("android-res"), In.config()));
                for (String rel : ctx.config().stringList("proguard-files")) {
                    r8Inputs.add(In.projectFiles(rel));
                }
                ctx.task(TaskSpec.named("android-r8")
                        .stage("package")
                        .inputs(r8Inputs.toArray(new In[0]))
                        .outputs("dex", "mapping")
                        .run(R8Step::run));
            } else {
                dexStep = "android-dex";
                ctx.task(TaskSpec.named("android-dex")
                        .stage("package")
                        .inputs(In.classes(), In.runtimeEntries(), In.config())
                        .outputs("dex")
                        .run(DexStep::run));
            }
            // The signing identity is part of the artifact, so the keystore is a declared input
            // like any other file: rotating a key at the same path re-signs instead of restoring
            // an artifact that still carries the old signature. An AAR is never signed.
            if (release) {
                // The release artifact is the Play-uploadable AAB ([[packaging.variant]] picks
                // the extension); bundletool assembles from the proto-format link.
                ctx.packaging(PackagerSpec.replacingMainArtifact("aab")
                        .inputs(
                                In.stepOutput("android-res"),
                                In.stepOutput(dexStep),
                                In.runtimeEntries(),
                                In.projectFiles("assets"),
                                In.projectFiles("src/main/assets"),
                                Signing.keystoreInput(ctx.config()),
                                In.config())
                        .produce(AabPackager::produce));
            } else {
                ctx.packaging(PackagerSpec.replacingMainArtifact("apk")
                        .inputs(
                                In.stepOutput("android-res"),
                                In.stepOutput(dexStep),
                                In.runtimeEntries(),
                                In.projectFiles("assets"),
                                In.projectFiles("src/main/assets"),
                                Signing.keystoreInput(ctx.config()),
                                In.config())
                        .produce(ApkPackager::produce));
            }
            ctx.command(PluginCommandSpec.named("deploy")
                    .description("Install the built APK/AAB on a device and launch it")
                    .run(DeployCommand::run));
            ctx.command(PluginCommandSpec.named("instrument")
                    .description("Run instrumented tests on a device (am instrument)")
                    .run(InstrumentCommand::run));
        }
        ctx.command(PluginCommandSpec.named("android")
                .description("Android SDK provisioning: licenses, component status")
                .run(AndroidCommand::run));
        ctx.command(PluginCommandSpec.named("avd")
                .description("Managed virtual devices: create, list, boot headless")
                .run(AvdCommand::run));
    }
}
