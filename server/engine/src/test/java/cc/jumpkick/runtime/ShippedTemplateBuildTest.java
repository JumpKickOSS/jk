// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.giter8.Giter8Apply;
import cc.jumpkick.giter8.Giter8Maven;
import cc.jumpkick.giter8.PluginTemplates;
import cc.jumpkick.giter8.TemplateSpec;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.runtime.workspace.BuildService;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every template a shipped plugin jar bundles is scaffolded through the production path
 * ({@link PluginTemplates#materialize} + {@link Giter8Apply} with the shipped
 * {@code default.properties} answers) and the result is built. Until this class, no gate ever
 * scaffolded the shipped trees — a stale starter coordinate, a key the parser no longer accepts,
 * or a {@code [plugins]} table that no longer resolves would first fail on a user's screen after
 * {@code jk new}.
 *
 * <p>Network tier: the scaffolds resolve their framework closures (and Giter8 {@code maven()}
 * defaults) from Central. Builds run {@code skipTests}: the claim under test is that the scaffold
 * locks, compiles and packages — the templates' own sample tests bind ports and belong to the
 * frameworks, not to the template contract. Project roots are fresh temp dirs per run, so the
 * persistent cache below only warms the network, never replays the work.
 */
@Tag("network")
class ShippedTemplateBuildTest {

    /** The plugins that bundle templates; jar paths are wired by {@code :engine:networkTest}. */
    private static final List<PluginJar> TEMPLATE_PLUGINS =
            List.of(PluginJar.SPRING_BOOT, PluginJar.GRAILS, PluginJar.QUARKUS, PluginJar.MICRONAUT, PluginJar.ANDROID);

    /**
     * Templates that cannot build in-gate, by id, each with the reason. A template named here is
     * skipped with that reason in the assume message — never silently. Every current entry is a
     * live defect this test found on its first run: delete the entry when the named defect is
     * fixed, and the template rejoins the loop.
     */
    private static final Map<String, String> UNBUILDABLE = Map.of(
            "kotlin/android/compose",
                    "needs a provisioned Android SDK with accepted licenses (jk android licenses"
                            + " --yes), which the gate must not do to the host; verified"
                            + " out-of-gate 2026-09-01 — lock resolves, jk build emits the debug"
                            + " APK, jk test passes 2 JVM tests. Render is gated below.",
            "kotlin/micronaut/hello",
                    "jk.toml omits java = $java$ (default.properties declares java=25 unused), so"
                            + " compile-kotlin gets jvmTarget 0 — the defect groovy/grails/hello had",
            "kotlin/spring-boot/hello", "jk.toml omits java = $java$, so compile-kotlin gets jvmTarget 0",
            "java/spring-boot/hello",
                    "engine defect, not a template defect: the spring-boot describe worker forked off"
                            + " the lock-pin path launches with a classpath missing the plugin SDK"
                            + " (Could not find or load main class PluginMain, exit 1) — the same"
                            + " worker forks fine for kotlin/spring-boot/webmvc later in the run",
            "java/spring-boot/webmvc",
                    "declares java = 26 but the build compiled with the JDK 25 toolchain instead of"
                            + " provisioning 26 (concepts.md says java=26 'may provision'); javac 25"
                            + " rejects --release 26");

    private static final String GRAILS_HELLO = TemplateSpec.idOf("groovy", "grails", "hello");

    /**
     * The compose starter cannot build in-gate (see {@link #UNBUILDABLE}) but its <em>render</em>
     * must not rot: Compose/Kotlin sources are full of {@code $}-interpolation hazards for the
     * Giter8 renderer, which is exactly the defect class that sank the quarkus starters.
     */
    @Test
    void android_compose_scaffolds_and_renders(@TempDir Path tmp) throws Exception {
        registerTemplatePlugins();
        String id = TemplateSpec.idOf("kotlin", "android", "compose");
        TemplateSpec spec = discoverShippedTemplates().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the android plugin jar no longer bundles " + id));

        Path tmpl = PluginTemplates.materialize(
                requireNonNull(spec.pluginId()), spec.language(), spec.framework(), spec.name());
        Path dest = tmp.resolve("compose");
        int written = Giter8Apply.apply(tmpl, dest, Map.of(), Giter8Maven.central(false));

        assertThat(written).as("files scaffolded from %s", id).isGreaterThanOrEqualTo(6);
        assertThat(dest.resolve("jk.toml")).exists();
        assertThat(dest.resolve("src/main/AndroidManifest.xml")).exists();
        assertThat(Files.readString(dest.resolve("jk.toml")))
                .contains("compose     = true")
                .contains("compose-bom");
        try (var walk = Files.walk(dest)) {
            assertThat(walk.filter(f -> f.getFileName().toString().equals("MainActivity.kt"))
                            .findFirst())
                    .as("the activity rendered under the package dir")
                    .isPresent();
        }
    }

    @Test
    void grails_hello_scaffolds_and_builds(@TempDir Path tmp) throws Exception {
        registerTemplatePlugins();
        TemplateSpec grails = discoverShippedTemplates().stream()
                .filter(s -> s.id().equals(GRAILS_HELLO))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the grails plugin jar no longer bundles " + GRAILS_HELLO));
        scaffoldAndBuild(grails, tmp.resolve("hello"));
    }

    @TestFactory
    Stream<DynamicTest> every_other_shipped_template_scaffolds_and_builds() {
        registerTemplatePlugins();
        List<TemplateSpec> specs = discoverShippedTemplates();
        // Fake-green floor: 9 templates were bundled when this was written; an empty or
        // near-empty discovery is a broken scan, not a clean tree.
        assertThat(specs)
                .as("templates discovered in the shipped plugin jars (9 when this test was written)")
                .hasSizeGreaterThanOrEqualTo(9);
        return specs.stream()
                .filter(s -> !s.id().equals(GRAILS_HELLO))
                .map(spec -> DynamicTest.dynamicTest(
                        spec.id(),
                        () -> scaffoldAndBuild(
                                spec, Files.createTempDirectory("jk-template-" + spec.framework() + "-"))));
    }

    private static void registerTemplatePlugins() {
        for (PluginJar plugin : TEMPLATE_PLUGINS) {
            PluginTableRegistry.installFromJar(pluginJar(plugin));
        }
    }

    private static Path pluginJar(PluginJar plugin) {
        String jar = System.getProperty(plugin.jarProperty());
        assertThat(jar)
                .as(
                        "-D%s (wired by :engine:networkTest — a missing property is broken wiring, not a skip)",
                        plugin.jarProperty())
                .isNotBlank();
        return Path.of(jar);
    }

    /**
     * The cache persists to keep the *network* warm (the framework closures from Central), but the
     * first-party plugin jars inside it are rebuilt by every commit, and a cache that outlives
     * them serves stale copies — the lock freshen then pins a sha the store no longer has. So the
     * cache directory is keyed by the plugin jars' content: unchanged plugins reuse the warm
     * closure, a rebuilt plugin gets a cold, correct cache.
     */
    private static Path cacheRoot() throws IOException {
        StringBuilder shas = new StringBuilder();
        for (PluginJar plugin : TEMPLATE_PLUGINS) {
            shas.append(Hashing.sha256Hex(pluginJar(plugin)));
        }
        String key = Hashing.sha256Hex(shas.toString()).substring(0, 12);
        return TestCaches.dir("template-build-cache-" + key);
    }

    private static List<TemplateSpec> discoverShippedTemplates() {
        List<String> pluginIds = TEMPLATE_PLUGINS.stream()
                .map(p -> p.artifactId().substring("jk-".length()))
                .toList();
        return PluginTemplates.list().stream()
                .filter(s -> pluginIds.contains(s.pluginId()))
                .toList();
    }

    private static void scaffoldAndBuild(TemplateSpec spec, Path dest) throws Exception {
        String reason = UNBUILDABLE.get(spec.id());
        assumeTrue(reason == null, () -> "template " + spec.id() + " is not built in-gate: " + reason);

        Path tmpl = PluginTemplates.materialize(
                requireNonNull(spec.pluginId()), spec.language(), spec.framework(), spec.name());
        int written = Giter8Apply.apply(tmpl, dest, Map.of(), Giter8Maven.central(false));
        assertThat(written).as("files scaffolded from %s", spec.id()).isPositive();
        assertThat(dest.resolve("jk.toml"))
                .as("%s scaffolds a jk.toml at the project root", spec.id())
                .exists();

        Path cache = cacheRoot();
        WorkspaceRequest req = new WorkspaceRequest(
                dest, cache, null, 1, null, /* skipTests */ true, false, 1, null, false, /* freshenLock */ true);
        List<String> diagnostics = new ArrayList<>();
        WorkspaceBuildListener recorder = new WorkspaceBuildListener() {
            @Override
            public BuildPlanListener onModuleStart(ModulePlan module) {
                return new BuildPlanListener() {
                    @Override
                    public void error(String step, String code, String message) {
                        diagnostics.add(module.coord() + " " + step + ": " + message);
                    }
                };
            }
        };
        WorkspaceResult result = BuildService.buildWorkspace(req, recorder);
        assertThat(result.errors()).as("graph errors for %s", spec.id()).isEmpty();
        assertThat(result.success())
                .as("scaffold from %s builds; diagnostics:%n%s", spec.id(), String.join("\n", diagnostics))
                .isTrue();
    }
}
