// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Variants;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.tool.TrustedPlugins;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A packager packs the classes a classes transform (bytecode enhancement) wrote, not the
 * compiler's, so the forecast keys it on the transform's output as the live step does: the main
 * artifact's plugin packager, the minified jar's and jk's plain jar alike.
 */
class PluginPackagerTransformKeyTest {

    private static final String DESCRIBE_TRANSFORM =
            "{\"t\":\"task\",\"name\":\"enhance\",\"inputs\":[\"classes\"],\"outputs\":[\"classes\"],"
                    + "\"transformsClasses\":\"classes\"}";

    private static final String DESCRIBE_PACKAGER =
            "{\"t\":\"packager\",\"name\":\"pack\",\"inputs\":[\"classes\",\"config\"]}";

    @TempDir
    Path tmp;

    @AfterEach
    void clearTrustSeam() {
        System.clearProperty("jk.trust.state.dir");
    }

    @Test
    void the_main_artifact_packager_is_forecast_on_the_transformed_classes() throws Exception {
        Path dir = pluginProject(true);
        JkBuild build = build(dir);
        seedDescribeCache(dir, build, DESCRIBE_TRANSFORM, DESCRIBE_PACKAGER);
        BuildLayout layout = BuildLayout.of(dir, build);
        Path cache = tmp.resolve("cache");
        Path transformed = compileAndTransform(build, layout, cache);

        var owner = requireNonNull(PackagingKeys.pluginFor(build, layout, cache));
        assertThat(PackagingKeys.ownsPackaging(owner)).isTrue();
        ActivePlugin packager = requireNonNull(owner.packager());
        Path artifact = ActivePlugins.mainArtifactPath(layout, packager);
        // What PackageSteps' live package-jar keys: the classes BuildPlanner.mainClasses hands it.
        PackagingKeys.Keyed live = key(build, dir, layout, transformed, artifact, packager, owner.decls());
        assertThat(key(build, dir, layout, layout.classesDir(), artifact, packager, owner.decls())
                        .key())
                .as("the compiler's classes would key another jar")
                .isNotEqualTo(live.key());

        Files.createDirectories(requireNonNull(artifact.getParent()));
        Files.writeString(artifact, "packed");
        PackagingCache.storePackagedForTest(
                cache,
                live.taskId(),
                live.key(),
                live.tokens(),
                requireNonNull(artifact.getParent()),
                List.of(artifact),
                true,
                List.of());

        TaskForecast.Task forecast = PackagingKeys.pluginPackagerStep(
                build,
                dir,
                layout,
                cache,
                dir.resolve("jk-lock.toml"),
                cas(),
                javaHome(),
                owner,
                PackagingCache.packagingActionCache(cache));
        assertThat(forecast.cached()).as(forecast.text()).isTrue();
        assertThat(forecast.key()).isEqualTo(ForecastSteps.key8(live.key()));
    }

    @Test
    void the_minified_jar_is_forecast_on_the_transformed_classes() throws Exception {
        Path dir = pluginProject(false);
        JkBuild build = build(dir);
        seedDescribeCache(dir, build, DESCRIBE_TRANSFORM, DESCRIBE_PACKAGER);
        BuildLayout layout = BuildLayout.of(dir, build);
        Path cache = tmp.resolve("cache");
        Path transformed = compileAndTransform(build, layout, cache);

        ActivePlugin minifier = ActivePlugins.besideMainArtifact(build, dir).orElseThrow();
        // What PlannerTails.minifiedStep keys: the minifier's own declarations over mainClasses.
        PluginDeclarations decls = PluginBuild.declarations(minifier, build, dir, cache, layout.moduleTargetDir());
        PackagingKeys.Keyed live = key(build, dir, layout, transformed, layout.minifiedJar(), minifier, decls);
        assertThat(key(build, dir, layout, layout.classesDir(), layout.minifiedJar(), minifier, decls)
                        .key())
                .as("the compiler's classes would key another jar")
                .isNotEqualTo(live.key());

        PackagingKeys.Keyed forecast = requireNonNull(
                PackagingKeys.minified(build, dir, cache, dir.resolve("jk-lock.toml"), cas(), layout, javaHome()));
        assertThat(forecast.key()).isEqualTo(live.key());
    }

    @Test
    void the_plain_jar_is_forecast_on_the_transformed_classes() throws Exception {
        Path dir = pluginProject(null);
        JkBuild build = build(dir);
        seedDescribeCache(dir, build, DESCRIBE_TRANSFORM);
        BuildLayout layout = BuildLayout.of(dir, build);
        Path cache = tmp.resolve("cache");
        Path transformed = compileAndTransform(build, layout, cache);
        assertThat(PackagingKeys.ownsPackaging(PackagingKeys.pluginFor(build, layout, cache)))
                .isFalse();

        // What PackageSteps' live package-jar keys: its guard's token of BuildPlanner.mainClasses.
        Path jar = layout.mainJar();
        String live = PackagingKeys.packageJar(
                        jar, dir, build, InputGuard.of(List.of(transformed)).token(transformed), "", null)
                .key();
        String forecast = requireNonNull(
                PackagingKeys.transformedClassesToken(build, layout, cache, ClasspathFingerprint.ON_DISK));
        assertThat(PackagingKeys.packageJar(jar, dir, build, forecast, "", null).key())
                .isEqualTo(live);
        String compiled = ClasspathFingerprint.entry(layout.classesDir());
        assertThat(PackagingKeys.packageJar(jar, dir, build, compiled, "", null).key())
                .as("the compiler's classes would key another jar")
                .isNotEqualTo(live);
    }

    @Test
    void the_assembly_is_forecast_on_the_transformed_classes() throws Exception {
        Path dir = pluginProject(null);
        Files.writeString(
                dir.resolve("jk.toml"),
                "\n[application]\nmain = \"ex.A\"\nassembly = true\n",
                StandardOpenOption.APPEND);
        JkBuild build = build(dir);
        assertThat(build.assembly()).isTrue();
        seedDescribeCache(dir, build, DESCRIBE_TRANSFORM);
        BuildLayout layout = BuildLayout.of(dir, build);
        Path cache = tmp.resolve("cache");
        Path transformed = compileAndTransform(build, layout, cache);
        Path lockFile = dir.resolve("jk-lock.toml");

        // What PlannerTails.assemblyStep keys: its guard's token of BuildPlanner.mainClasses.
        List<Path> contributed = PackagedClasses.packagedDirs(
                PackagedClasses.pluginDeclarationsFor(build, layout, cache), layout, build);
        List<Path> depJars = PackagedClasses.assemblyDependencyJars(dir, build, lockFile, cache);
        PackagingKeys.Keyed live = PackagingKeys.assembly(
                layout.assemblyJar(),
                dir,
                build,
                PackagingKeys.assemblyAttributes(dir, build),
                InputGuard.of(List.of(transformed)).token(transformed),
                PackagedClasses.contributionsToken(contributed),
                ClasspathFingerprint.ordered(depJars),
                PackagingKeys.assemblySbom(build, dir, lockFile));
        Path jar = layout.assemblyJar();
        Files.createDirectories(requireNonNull(jar.getParent()));
        Files.writeString(jar, "fat");
        PackagingCache.storePackagedForTest(
                cache,
                live.taskId(),
                live.key(),
                live.tokens(),
                requireNonNull(jar.getParent()),
                List.of(jar),
                true,
                List.of());

        assertThat(PackagingKeys.assemblyActionCached(
                        dir,
                        build,
                        layout,
                        lockFile,
                        PackagingCache.packagingActionCache(cache),
                        cache,
                        null,
                        Map.of(),
                        false,
                        null,
                        ClasspathFingerprint.ON_DISK))
                .as("the forecast recomputes the key the build stored")
                .isTrue();
    }

    @Test
    void a_module_no_plugin_transforms_keys_its_compiled_classes() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("plain"));
        Files.writeString(
                dir.resolve("jk.toml"), "group = \"com.demo\"\nname = \"plain\"\nversion = \"0.1.0\"\njava = 25\n");
        JkBuild build = build(dir);
        BuildLayout layout = BuildLayout.of(dir, build);

        assertThat(PackagingKeys.transformedClassesToken(
                        build, layout, tmp.resolve("cache"), ClasspathFingerprint.ON_DISK))
                .isNull();
    }

    // ---- fixture ------------------------------------------------------------------------------

    /** Compiled classes, and the transform's rewrite of them where the transform step writes it. */
    private static Path compileAndTransform(JkBuild build, BuildLayout layout, Path cache) throws Exception {
        Path compiled = Files.createDirectories(layout.classesDir().resolve("ex"));
        Files.writeString(compiled.resolve("A.class"), "compiled");
        Path transformed = requireNonNull(PluginBuild.transformOutput(build, layout, cache));
        Files.createDirectories(transformed.resolve("ex"));
        Files.writeString(transformed.resolve("ex/A.class"), "enhanced");
        return transformed;
    }

    private PackagingKeys.Keyed key(
            JkBuild build,
            Path dir,
            BuildLayout layout,
            Path classes,
            Path artifact,
            ActivePlugin active,
            PluginDeclarations decls)
            throws Exception {
        return PackagingKeys.pluginPackager(new PackagingKeys.Packager(
                        build,
                        dir,
                        tmp.resolve("cache"),
                        dir.resolve("jk-lock.toml"),
                        cas(),
                        new PluginBuild.StepTools(),
                        layout,
                        classes,
                        artifact,
                        javaHome(),
                        active,
                        decls,
                        BuildEnv.ambient()))
                .keyed();
    }

    private Cas cas() {
        return new Cas(tmp.resolve("cache"));
    }

    private static Path javaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    /** The build the planner keys: variant overlays folded into plugin configs. */
    private static JkBuild build(Path dir) throws Exception {
        return VariantApply.apply(
                JkBuildParser.reparse(dir.resolve("jk.toml")), dir, Variants.Selection.parse(""), Map.of());
    }

    /**
     * A Java module with a path-pinned, materialized plugin that transforms the classes and
     * packages them, as the main artifact or beside it; with {@code mainArtifact} null it packages
     * nothing, and jk packs the plain jar.
     */
    private Path pluginProject(@Nullable Boolean mainArtifact) throws Exception {
        Path jar = writePluginJar(tmp.resolve("vendor").resolve("fake-1.0.0.jar"), mainArtifact);
        String hex = Hashing.sha256Hex(jar);

        Path dir = Files.createDirectories(tmp.resolve("proj"));
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(dir.resolve("src/main/java/ex/A.java"), "package ex; class A {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "com.demo"
                version = "0.1.0"
                java = 25

                [plugins]
                fake = { path = "%s", sha256 = "%s" }

                [fake]
                enabled = true
                """.formatted(
                        dir.relativize(jar).toString().replace('\\', '/'), hex));

        // A path pin forks the sha-verified blob in the store.
        Path casJar = JkStores.storeCas().putFile(jar, hex);
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of(new Lockfile.PluginEntry("path:fake", "local", "sha256:" + hex))),
                dir.resolve("jk-lock.toml"));
        PluginDescriptorOps.materialize(dir, hex, casJar, "path:fake");
        // The packager's worker jar is a key input, and jk reads it only from a trusted plugin.
        Path state = Files.createDirectories(tmp.resolve("state"));
        System.setProperty("jk.trust.state.dir", state.toString());
        ActivePlugin active = ActivePlugins.of(JkBuildParser.reparse(dir.resolve("jk.toml")), dir)
                .getFirst();
        TrustedPlugins.load(state).add(PluginBuild.trustKey(requireNonNull(active.declaration())));
        return dir;
    }

    /** Pre-seed the content-keyed describe cache so nothing forks a plugin worker. */
    private void seedDescribeCache(Path dir, JkBuild build, String... lines) throws Exception {
        ActivePlugin active = ActivePlugins.of(build, dir).getFirst();
        Path target = BuildLayout.of(dir, build).moduleTargetDir();
        String key = PluginBuild.describeKey(active, build, PluginBuild.locateWorkerJar(active, tmp.resolve("cache")));
        Path cacheFile = target.resolve("plugin").resolve("fake-describe-" + key + ".jsonl");
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, List.of(lines), StandardCharsets.UTF_8);
    }

    private static Path writePluginJar(Path jar, @Nullable Boolean mainArtifact) throws Exception {
        Files.createDirectories(jar.getParent());
        String packaging = mainArtifact == null ? "" : """
                [packaging]
                packager      = "pack"
                main-artifact = %s

                """.formatted(mainArtifact);
        String manifest = """
                [plugin]
                id      = "fake"
                table   = "fake"
                version = "1.0.0"

                [schema]
                enabled = { type = "bool", default = true }

                %s[code]
                protocol-prefix = "##FAKE:"
                """.formatted(packaging);
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, mf)) {
            jos.putNextEntry(new JarEntry("jk-plugin.toml"));
            jos.write(manifest.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }
}
