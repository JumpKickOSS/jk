// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code for-step} scopes a step-dependency to the steps or packagers that read it: the rest of
 * the plugin neither fetches it nor keys on it. Unscoped, every step of a plugin paid for every
 * tool the plugin declared — an android debug build materialized bundletool's closure for an AAB
 * packager it never registered, and bumping any one tool re-ran every step.
 *
 * <p>The tools here are files under a fake managed SDK root ({@code sdk-component = "root"}), so
 * "fetching" one is resolving a path that must exist: a tool whose file is absent cannot be
 * fetched, which is what makes "never fetched" observable.
 */
class StepToolScopeTest {

    private static final Path ANDROID_MANIFEST =
            RepoRoot.file(StepToolScopeTest.class, "plugins/android/jk-plugin.toml");

    @TempDir
    Path tmp;

    private Path sdk;
    private @Nullable String oldRoot;

    @BeforeEach
    void fakeSdkRoot() throws IOException {
        sdk = Files.createDirectories(tmp.resolve("sdk"));
        oldRoot = System.getProperty(AndroidSdk.ROOT_PROPERTY);
        System.setProperty(AndroidSdk.ROOT_PROPERTY, sdk.toString());
    }

    @AfterEach
    void restoreSdkRoot() {
        if (oldRoot == null) System.clearProperty(AndroidSdk.ROOT_PROPERTY);
        else System.setProperty(AndroidSdk.ROOT_PROPERTY, oldRoot);
    }

    /** Two steps, one tool each; {@code shared} is unscoped and reaches both. */
    private static JkBuild twoStepBuild() {
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id = "scope-fixture"
                        table = "scope-fixture"

                        [[contribute.step-dependency]]
                        artifact = "tool-a"
                        sdk-component = "root"
                        sdk-path = "tool-a"
                        for-step = "step-a"

                        [[contribute.step-dependency]]
                        artifact = "tool-b"
                        sdk-component = "root"
                        sdk-path = "tool-b"
                        for-step = "step-b"

                        [[contribute.step-dependency]]
                        artifact = "shared"
                        sdk-component = "root"
                        sdk-path = "shared"
                        """, "scope-fixture.toml"), null);
        return JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                java = 25

                [scope-fixture]
                """);
    }

    private Path tool(String name, String content) throws IOException {
        return Files.writeString(sdk.resolve(name), content);
    }

    @Test
    void step_a_runs_without_fetching_step_b_tool() throws Exception {
        JkBuild build = twoStepBuild();
        tool("tool-a", "a");
        tool("shared", "s");
        // tool-b has no file: any attempt to fetch it fails, so a successful step-a fetch proves
        // step-b's tool was never touched.
        Cas cas = new Cas(tmp.resolve("cas"));
        PluginBuild.StepTools tools = new PluginBuild.StepTools();

        List<PluginContributions.StepDep> forA = tools.forConsumer(build, tmp, "step-a");
        assertThat(forA).extracting(PluginContributions.StepDep::artifact).containsExactly("tool-a", "shared");
        assertThat(tools.fetch(forA, build, cas, Map.of())).containsOnlyKeys("tool-a", "shared");

        assertThatThrownBy(() -> tools.fetch(tools.forConsumer(build, tmp, "step-b"), build, cas, Map.of()))
                .as("step-b's own slice is the one that needs the missing file")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("tool-b");
    }

    @Test
    void bumping_step_b_tool_does_not_move_step_a_key() throws Exception {
        JkBuild build = twoStepBuild();
        tool("tool-a", "a 1.0");
        tool("tool-b", "b 1.0");
        tool("shared", "s 1.0");
        Cas cas = new Cas(tmp.resolve("cas"));

        List<String> aBefore = stepTokens(build, cas, "step-a");
        List<String> bBefore = stepTokens(build, cas, "step-b");
        tool("tool-b", "b 2.0 — a different binary");

        assertThat(stepTokens(build, cas, "step-a"))
                .as("step-a never reads tool-b, so its key must not know about the bump")
                .isEqualTo(aBefore)
                .hasSize(2);
        assertThat(stepTokens(build, cas, "step-b"))
                .as("step-b does read it, so its key moves")
                .isNotEqualTo(bBefore);
    }

    /** The action-key tool tokens one build renders for {@code step}: a fresh build's lane and fetch. */
    private List<String> stepTokens(JkBuild build, Cas cas, String step) throws Exception {
        PluginBuild.StepTools tools = new PluginBuild.StepTools();
        List<PluginContributions.StepDep> declared = tools.forConsumer(build, tmp, step);
        return PlannerPlugin.toolTokens(declared, tools.fetch(declared, build, cas, Map.of()), Map.of());
    }

    @Test
    void a_tool_fetched_once_is_handed_to_the_next_consumer_without_a_second_resolve() throws Exception {
        JkBuild build = twoStepBuild();
        Path shared = tool("shared", "s");
        tool("tool-a", "a");
        tool("tool-b", "b");
        Cas cas = new Cas(tmp.resolve("cas"));
        PluginBuild.StepTools tools = new PluginBuild.StepTools();

        tools.fetch(tools.forConsumer(build, tmp, "step-a"), build, cas, Map.of());
        // Gone from disk: a second resolve of `shared` would now fail. The build already has it.
        Files.delete(shared);

        assertThat(tools.fetch(tools.forConsumer(build, tmp, "step-b"), build, cas, Map.of()))
                .containsEntry("shared", shared)
                .containsKey("tool-b");
    }

    @Test
    void a_named_lookup_ignores_scope() throws Exception {
        JkBuild build = twoStepBuild();
        PluginBuild.StepTools tools = new PluginBuild.StepTools();

        assertThat(tools.named(build, tmp, List.of("tool-b", "unknown")))
                .as("[[contribute.provided-classpath]] names its tool; whose step it is does not matter")
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("tool-b");
    }

    /**
     * The shipped android manifest, partitioned the way a debug build's consumers see it: nothing
     * a debug build registers reaches bundletool, and each step gets exactly what its body reads.
     */
    @Test
    void the_android_debug_lane_never_contains_bundletool() throws Exception {
        PluginDescriptor manifest =
                PluginDescriptors.parse(Files.readString(ANDROID_MANIFEST), ANDROID_MANIFEST.toString());
        PluginTableRegistry.putBuiltIn(manifest, null);
        JkBuild build = JkBuildParser.parse("""
                name = "hello"
                group = "com.example"
                version = "0.1.0"
                java = 25

                [android]
                namespace = "com.example.hello"
                compile-sdk = 36
                min-sdk = 24
                """);
        PluginBuild.StepTools tools = new PluginBuild.StepTools();

        for (String consumer :
                List.of("android-manifest", "android-res", "android-test-config", "android-dex", "apk")) {
            assertThat(tools.forConsumer(build, tmp, consumer))
                    .as("debug consumer %s", consumer)
                    .extracting(PluginContributions.StepDep::artifact)
                    .doesNotContain("bundletool");
        }
        assertThat(tools.forConsumer(build, tmp, "aab"))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("bundletool");
        assertThat(tools.forConsumer(build, tmp, "android-manifest"))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("manifest-merger");
        assertThat(tools.forConsumer(build, tmp, "android-res"))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("aapt2", "android-jar");
        assertThat(tools.forConsumer(build, tmp, "android-dex"))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("r8", "android-jar");
        assertThat(tools.forConsumer(build, tmp, "android-test-config")).isEmpty();
        assertThat(tools.named(build, tmp, PluginContributions.providedClasspath(build, tmp)))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("android-jar");
    }
}
