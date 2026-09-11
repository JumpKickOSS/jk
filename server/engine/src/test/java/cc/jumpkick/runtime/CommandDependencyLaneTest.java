// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [[contribute.command-dependency]]} exists so a command-only tool leaves the step-key
 * vocabulary: the build provisions and keys the step lane alone, while plugin commands receive
 * both lanes. Before the split, android's {@code adb} sat in the step lane, so upgrading the
 * installed platform-tools revision invalidated every android compile and package action that
 * never touched it.
 *
 * <p>The lane cases here run against the <em>shipped</em> android manifest — the one measured
 * defect — not a fixture shaped like it, so moving {@code adb} back to the step lane turns them
 * red.
 */
class CommandDependencyLaneTest {

    private static final Path ANDROID_MANIFEST =
            RepoRoot.file(CommandDependencyLaneTest.class, "plugins/android/jk-plugin.toml");

    private static final String ANDROID_PROJECT = """
            name = "hello"
            group = "com.example"
            version = "0.1.0"
            java = 25

            [android]
            namespace = "com.example.hello"
            compile-sdk = 36
            min-sdk = 24
            """;

    /** The android manifest, parsed from source and installed the way the engine installs it. */
    private static JkBuild androidBuild() throws IOException {
        PluginDescriptor manifest =
                PluginDescriptors.parse(Files.readString(ANDROID_MANIFEST), ANDROID_MANIFEST.toString());
        PluginTableRegistry.putBuiltIn(manifest, null);
        return JkBuildParser.parse(ANDROID_PROJECT);
    }

    /**
     * The measured split: of android's seven tool artifacts, the two only commands read sit in
     * the command lane; the step lane keeps the five a step or packager reads — including
     * {@code bundletool} and {@code aapt2}, which commands borrow but steps own.
     */
    @Test
    void the_android_command_only_tools_sit_in_the_command_lane() throws Exception {
        JkBuild build = androidBuild();

        assertThat(PluginContributions.stepDependencies(build, null))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactlyInAnyOrder("aapt2", "r8", "manifest-merger", "android-jar", "bundletool");
        assertThat(PluginContributions.commandDependencies(build, null))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactlyInAnyOrder("adb", "sdk-root");
    }

    /**
     * The goal itself, asserted on the token list {@code toolTokens} returns — the one renderer
     * both the step arm and the packager arm call: an adb content change plus a platform-tools
     * revision bump moves no token, because the command lane reaches neither the declared list
     * nor the fetched extras a build keys.
     */
    @Test
    void a_platform_tools_change_moves_no_step_or_packager_token(@TempDir Path tmp) throws Exception {
        JkBuild build = androidBuild();
        List<PluginContributions.StepDep> declared = PluginContributions.stepDependencies(build, null);
        Files.writeString(tmp.resolve("adb"), "platform-tools 35.0.2's adb");

        List<String> before =
                PlannerPlugin.toolTokens(declared, stepExtras(declared, tmp), Map.of("platform-tools", "35.0.2"));
        Files.writeString(tmp.resolve("adb"), "platform-tools 36.0.0's adb — a different binary");
        List<String> after =
                PlannerPlugin.toolTokens(declared, stepExtras(declared, tmp), Map.of("platform-tools", "36.0.0"));

        assertThat(after)
                .as("upgrading platform-tools must not invalidate an android compile or package")
                .isEqualTo(before);
        assertThat(before).hasSize(5).allSatisfy(token -> assertThat(token).doesNotContain("adb", "sdk-root"));
    }

    /**
     * One stand-in fetched file per declared step-lane artifact — the {@code adb} file is on disk
     * either way, so if {@code adb} rejoins the declared step lane it rejoins the extras and its
     * changed content moves a token.
     */
    private static Map<String, Path> stepExtras(List<PluginContributions.StepDep> declared, Path tmp)
            throws IOException {
        Map<String, Path> extras = new LinkedHashMap<>();
        for (PluginContributions.StepDep dep : declared) {
            Path file = tmp.resolve(dep.artifact());
            if (!Files.exists(file)) Files.writeString(file, dep.artifact() + " bytes");
            extras.put(dep.artifact(), file);
        }
        return extras;
    }

    /**
     * The lock pins the command lane's sdk-components exactly like the step lane's: an offline
     * {@code jk run} deploy needs its platform-tools pin the way an offline build needs
     * android-jar's. A declaration moving lanes must not silently drop its row; the {@code [[sdk]]}
     * row itself is unchanged — component plus revision.
     */
    @Test
    void the_lock_still_pins_a_command_lane_sdk_component(@TempDir Path tmp) throws Exception {
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id = "cmdlane-pin-fixture"
                        table = "cmdlane-pin-fixture"

                        [[contribute.command-dependency]]
                        artifact = "adb"
                        sdk-component = "platform-tools"
                        sdk-path = "adb"
                        """, "cmdlane-pin-fixture.toml"), null);
        JkBuild build = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [cmdlane-pin-fixture]
                """);
        Path sdkRoot = Files.createDirectories(tmp.resolve("sdk/platform-tools"));
        Files.writeString(sdkRoot.resolve("source.properties"), "Pkg.Revision=35.0.2\n");
        Lockfile empty = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
        String oldRoot = System.getProperty(AndroidSdk.ROOT_PROPERTY);
        System.setProperty(AndroidSdk.ROOT_PROPERTY, tmp.resolve("sdk").toString());
        try {
            LockPipeline pipeline =
                    new LockPipeline(tmp, build, tmp.resolve("cache"), null, List.of(), false, new LockMode.Freshen());

            assertThat(pipeline.pinSdk(empty, LockPipeline.Progress.SILENT).sdk())
                    .containsExactly(new Lockfile.SdkEntry("platform-tools", "35.0.2"));
        } finally {
            if (oldRoot == null) System.clearProperty(AndroidSdk.ROOT_PROPERTY);
            else System.setProperty(AndroidSdk.ROOT_PROPERTY, oldRoot);
        }
    }

    /**
     * The provisioning split. The step-lane fetch — the whole lane here; a build's steps, packager
     * and compile classpath each take their slice of it through {@code PluginBuild.StepTools} —
     * never reaches the command lane, so a command-only artifact that cannot provision does not
     * fail (or even touch) a build. The command fetch does provision it, and leniently: {@code jk
     * android licenses} must run before any license gates provisioning, so a failed provision is
     * an absent extra, not an error.
     */
    @Test
    void a_build_never_provisions_a_command_only_tool_and_the_command_fetch_is_lenient(@TempDir Path tmp)
            throws Exception {
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id = "cmdlane-engine-fixture"
                        table = "cmdlane-engine-fixture"

                        [[contribute.command-dependency]]
                        artifact = "unprovisionable"
                        sdk-component = "root"
                        sdk-path = "not-installed-here"
                        """, "cmdlane-engine-fixture.toml"), null);
        JkBuild build = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [cmdlane-engine-fixture]
                """);
        Cas cas = new Cas(tmp.resolve("cas"));
        String oldRoot = System.getProperty(AndroidSdk.ROOT_PROPERTY);
        System.setProperty(AndroidSdk.ROOT_PROPERTY, tmp.resolve("sdk").toString());
        try {
            assertThat(PluginBuild.fetchStepDependencies(build, tmp, cas, Map.of(), false))
                    .as("a strict build fetch must not even attempt the command lane")
                    .isEmpty();
            assertThat(PluginBuild.fetchCommandDependencies(build, tmp, cas, Map.of(), true))
                    .as("lenient: the command that needs the tool reports the miss itself")
                    .isEmpty();
            assertThatThrownBy(() -> PluginBuild.fetchCommandDependencies(build, tmp, cas, Map.of(), false))
                    .as("the command lane IS fetched — strictly, it surfaces the failure")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("not-installed-here");
        } finally {
            if (oldRoot == null) System.clearProperty(AndroidSdk.ROOT_PROPERTY);
            else System.setProperty(AndroidSdk.ROOT_PROPERTY, oldRoot);
        }
    }
}
