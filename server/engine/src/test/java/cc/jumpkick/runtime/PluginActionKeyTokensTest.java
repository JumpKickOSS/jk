// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin's action key has one renderer for its declared inputs and one for the project facts.
 * Both are pinned here because the defect they replaced is silent: an input or a fact that reaches
 * a plugin body without reaching its key restores a stale artifact and reports it as up-to-date.
 */
class PluginActionKeyTokensTest {

    private static final String TOML = """
            name = "demo"
            group = "com.demo"
            version = "0.1.0"
            java = 25
            """;

    /**
     * Every kind in the closed {@code In} vocabulary is fingerprinted. The switch is exhaustive at
     * compile time; this pins that no arm renders nothing, which would drop the input from the key.
     */
    @Test
    void every_declared_input_kind_renders_a_token(@TempDir Path tmp) throws Exception {
        PlannerPlugin.InputSources src = sources(tmp);
        for (In.Kind kind : In.Kind.values()) {
            assertThat(PlannerPlugin.declaredInputTokens(List.of(wire(kind)), src))
                    .as("declared input %s", wire(kind))
                    .isNotEmpty();
        }
    }

    /**
     * The runtime view has one spelling. The step arm said {@code cp:} and the packager arm said
     * {@code libs:} for the same declared input, so the two arms keyed the same dependency set two
     * ways.
     */
    @Test
    void the_runtime_view_has_one_prefix_and_containers_ride_with_entries(@TempDir Path tmp) throws Exception {
        PlannerPlugin.InputSources src = sources(tmp);

        List<String> classpathOnly =
                PlannerPlugin.declaredInputTokens(List.of(In.runtimeClasspath().wireName()), src);
        assertThat(classpathOnly).hasSize(1);
        assertThat(classpathOnly.get(0)).startsWith("cp:");

        assertThat(PlannerPlugin.declaredInputTokens(List.of(In.runtimeEntries().wireName()), src))
                .anySatisfy(token -> assertThat(token).startsWith("cp:"))
                .anySatisfy(token -> assertThat(token).startsWith("container:dep-1.0.aar:"));
    }

    /**
     * A packager writes its entry list out verbatim — a boot jar's {@code classpath.idx} IS the
     * launcher's classpath order — but {@code cp:} is a content hash whose parts are sorted. So
     * reordering two dependencies with the identical resolved set produced the identical key and
     * restored a jar whose index still encoded the old order.
     */
    @Test
    void reordering_two_runtime_entries_changes_the_key(@TempDir Path tmp) throws Exception {
        List<PluginBuild.ProdEntry> entries = twoEntries(tmp);
        List<PluginBuild.ProdEntry> swapped = List.of(entries.get(1), entries.get(0));

        assertThat(entryTokens(tmp, swapped))
                .as("lib order is part of the artifact")
                .isNotEqualTo(entryTokens(tmp, entries));
        assertThat(entryTokens(tmp, entries)).isEqualTo(entryTokens(tmp, entries));
    }

    /**
     * The other two facts the content hash cannot see. {@code snapshot} is lockfile metadata that
     * decides which {@code layers.idx} layer an entry lands in; {@code fileName} and the coordinate
     * are written into the entry name and both index files. All three are identical bytes on disk.
     */
    @Test
    void snapshot_flag_file_name_and_coordinate_are_all_in_the_key(@TempDir Path tmp) throws Exception {
        PluginBuild.ProdEntry base = twoEntries(tmp).get(0);
        Path jar = base.jar();

        List<String> reference = entryTokens(tmp, List.of(base));
        assertThat(entryTokens(tmp, List.of(new PluginBuild.ProdEntry("a-1.0.jar", jar, true, null, "g", "a", "1.0"))))
                .as("release -> snapshot moves the entry between layers.idx layers")
                .isNotEqualTo(reference);
        assertThat(entryTokens(tmp, List.of(new PluginBuild.ProdEntry("z-1.0.jar", jar, false, null, "g", "a", "1.0"))))
                .as("the file name IS the BOOT-INF/lib entry name")
                .isNotEqualTo(reference);
        assertThat(entryTokens(tmp, List.of(new PluginBuild.ProdEntry("a-1.0.jar", jar, false, null, "h", "a", "1.0"))))
                .as("the group disambiguates a colliding entry name")
                .isNotEqualTo(reference);
    }

    /** Two release entries with distinct content, in lock order. */
    private static List<PluginBuild.ProdEntry> twoEntries(Path tmp) throws Exception {
        Path a = Files.writeString(tmp.resolve("a-1.0.jar"), "a");
        Path b = Files.writeString(tmp.resolve("b-1.0.jar"), "b");
        return List.of(
                new PluginBuild.ProdEntry("a-1.0.jar", a, false, null, "g", "a", "1.0"),
                new PluginBuild.ProdEntry("b-1.0.jar", b, false, null, "g", "b", "1.0"));
    }

    /** The declared-input tokens for {@code runtime-entries} over exactly these entries. */
    private static List<String> entryTokens(Path tmp, List<PluginBuild.ProdEntry> entries) throws Exception {
        JkBuild project = JkBuildParser.parse(TOML);
        List<Path> jars = entries.stream().map(PluginBuild.ProdEntry::jar).toList();
        PlannerPlugin.InputSources src = new PlannerPlugin.InputSources(
                Files.createDirectories(tmp.resolve("classes")),
                jars,
                entries,
                new PluginConfig("fake", Map.of()),
                BuildLayout.of(tmp, project),
                tmp);
        return PlannerPlugin.declaredInputTokens(List.of(In.runtimeEntries().wireName()), src);
    }

    /** An input the engine cannot fingerprint is refused, not skipped — skipping it is a stale key. */
    @Test
    void an_input_the_engine_cannot_fingerprint_is_refused(@TempDir Path tmp) throws Exception {
        PlannerPlugin.InputSources src = sources(tmp);
        assertThatThrownBy(() -> PlannerPlugin.declaredInputTokens(List.of("secret-sauce"), src))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Every fact a plugin body receives changes the key. {@code nativeDeclared} and {@code kotlin}
     * used to reach the body and not the step key, so a project gaining a {@code [native]} table
     * restored the non-native artifact.
     */
    @Test
    void the_facts_token_moves_for_every_fact_a_plugin_body_sees() {
        ProjectFacts base =
                new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "v"));
        assertThat(List.of(
                        base.token(),
                        new ProjectFacts("other", "demo", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "other", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.2.0", 25, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 21, "ex.Main", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Other", false, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", true, false, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", false, true, Map.of("K", "v"))
                                .token(),
                        new ProjectFacts("com.demo", "demo", "0.1.0", 25, "ex.Main", false, false, Map.of("K", "w"))
                                .token()))
                .as("one distinct token per ProjectFacts component")
                .doesNotHaveDuplicates()
                .hasSize(ProjectFacts.class.getRecordComponents().length + 1);
    }

    /**
     * Adding a component to {@link ProjectFacts} must add it to {@link ProjectFacts#token()} in the
     * same change — this is the assertion that fails when it does not.
     */
    @Test
    void the_facts_token_covers_every_projectfacts_component() {
        assertThat(ProjectFacts.class.getRecordComponents())
                .as("render any new ProjectFacts component in token(), then widen the sweep above")
                .hasSize(8);
    }

    /** The [native] table and a kotlin version reach the body through facts(), so they key it. */
    @Test
    void a_declared_native_table_and_kotlin_change_the_facts_token() {
        JkBuild plain = JkBuildParser.parse(TOML);
        JkBuild nativeDeclared = JkBuildParser.parse(TOML + "\n[native]\nenabled = \"always\"\n");
        JkBuild kotlin = JkBuildParser.parse(TOML + "\nkotlin = \"2.1.0\"\n");

        assertThat(PluginBuild.facts(nativeDeclared, "ex.Main").token())
                .isNotEqualTo(PluginBuild.facts(plain, "ex.Main").token());
        assertThat(PluginBuild.facts(kotlin, "ex.Main").token())
                .isNotEqualTo(PluginBuild.facts(plain, "ex.Main").token());
    }

    /**
     * A provisioned SDK component named whole — {@code sdk-component} with no {@code sdk-path} — is
     * a location, not an artifact. Android's {@code sdk-root} resolves to the managed SDK root, so
     * fingerprinting it walked every installed platform, system image and emulator binary into
     * every android step's and packager's key: tens of gigabytes, on every build.
     */
    @Test
    void a_whole_sdk_component_is_keyed_by_revision_not_by_walking_it(@TempDir Path tmp) throws Exception {
        Path sdkRoot = Files.createDirectories(tmp.resolve("android-sdk/platforms/android-36"));
        Files.writeString(sdkRoot.resolve("android.jar"), "a platform");
        List<PluginContributions.StepDep> declared =
                List.of(new PluginContributions.StepDep("sdk-root", null, false, "root", null));
        Map<String, Path> extras = Map.of("sdk-root", tmp.resolve("android-sdk"));

        List<String> before = PlannerPlugin.toolTokens(declared, extras, Map.of());
        Files.writeString(tmp.resolve("android-sdk/system-image"), "60 GB of emulator, morally");

        assertThat(PlannerPlugin.toolTokens(declared, extras, Map.of()))
                .as("installing an unrelated SDK component must not re-run every android action")
                .isEqualTo(before);
        assertThat(before).containsExactly("tool:sdk-root:sdk:root@unpinned");
    }

    /** When the lock pins the component, the pin IS the identity — that is what `[[sdk]]` records. */
    @Test
    void a_pinned_component_carries_its_revision(@TempDir Path tmp) throws Exception {
        List<PluginContributions.StepDep> declared =
                List.of(new PluginContributions.StepDep("cmdline-tools", null, false, "cmdline-tools;latest", null));
        Map<String, Path> extras = Map.of("cmdline-tools", Files.createDirectories(tmp.resolve("tools")));

        assertThat(PlannerPlugin.toolTokens(declared, extras, Map.of("cmdline-tools;latest", "19.0")))
                .containsExactly("tool:cmdline-tools:sdk:cmdline-tools;latest@19.0");
    }

    /**
     * Everything the build actually reads stays content-keyed: a fetched jar, and a named path
     * inside an SDK component ({@code android-jar}, {@code adb}) — one file, not a tree.
     */
    @Test
    void fetched_artifacts_and_named_component_paths_stay_content_keyed(@TempDir Path tmp) throws Exception {
        Path aapt2 = Files.writeString(tmp.resolve("aapt2"), "v1");
        Path androidJar = Files.writeString(tmp.resolve("android.jar"), "platform-36");
        List<PluginContributions.StepDep> declared = List.of(
                new PluginContributions.StepDep("aapt2", "com.android.tools.build:aapt2:9.3.1"),
                new PluginContributions.StepDep("android-jar", null, false, "platforms;android-36", "android.jar"));
        Map<String, Path> extras = Map.of("aapt2", aapt2, "android-jar", androidJar);

        List<String> before = PlannerPlugin.toolTokens(declared, extras, Map.of());
        Files.writeString(aapt2, "v2");

        assertThat(PlannerPlugin.toolTokens(declared, extras, Map.of())).isNotEqualTo(before);
        assertThat(before).allSatisfy(token -> assertThat(token).doesNotContain(":sdk:"));
    }

    /**
     * Tools are keyed by name. The packager arm used to fold them into one order-insensitive
     * {@code extras:} content hash, which cannot tell {@code aapt2} from {@code adb} — swap the two
     * artifact names and the old token was identical.
     */
    @Test
    void a_tool_token_names_the_artifact_it_identifies(@TempDir Path tmp) throws Exception {
        Path first = Files.writeString(tmp.resolve("first"), "x");
        Path second = Files.writeString(tmp.resolve("second"), "y");
        List<PluginContributions.StepDep> declared = List.of(
                new PluginContributions.StepDep("aapt2", "g:aapt2:1"),
                new PluginContributions.StepDep("adb", "g:adb:1"));

        assertThat(PlannerPlugin.toolTokens(declared, Map.of("aapt2", first, "adb", second), Map.of()))
                .containsExactlyInAnyOrderElementsOf(
                        PlannerPlugin.toolTokens(declared, Map.of("aapt2", first, "adb", second), Map.of()));
        assertThat(PlannerPlugin.toolTokens(declared, ordered("aapt2", first, "adb", second), Map.of()))
                .isNotEqualTo(PlannerPlugin.toolTokens(declared, ordered("aapt2", second, "adb", first), Map.of()));
    }

    private static Map<String, Path> ordered(String a, Path pa, String b, Path pb) {
        Map<String, Path> map = new LinkedHashMap<>();
        map.put(a, pa);
        map.put(b, pb);
        return map;
    }

    private static PlannerPlugin.InputSources sources(Path tmp) throws Exception {
        JkBuild project = JkBuildParser.parse(TOML);
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(classes.resolve("App.class"), "fake");
        Path jar = Files.writeString(tmp.resolve("dep-1.0.jar"), "dep");
        Path exploded = Files.createDirectories(tmp.resolve("exploded"));
        Files.writeString(exploded.resolve("AndroidManifest.xml"), "<manifest/>");
        PluginBuild.ProdEntry entry = new PluginBuild.ProdEntry("dep-1.0.aar", jar, false, exploded);
        return new PlannerPlugin.InputSources(
                classes,
                List.of(jar),
                List.of(entry),
                new PluginConfig("fake", Map.of("enabled", Boolean.TRUE)),
                BuildLayout.of(tmp, project),
                tmp);
    }

    private static String wire(In.Kind kind) {
        return switch (kind) {
            case CLASSES, RUNTIME_CLASSPATH, RUNTIME_ENTRIES, TEST_RUNTIME_ENTRIES, CONFIG ->
                new In(kind, null).wireName();
            case STEP_OUTPUT -> In.stepOutput("aot").wireName();
            case PROJECT_FILES -> In.projectFiles("src/main/res").wireName();
        };
    }
}
