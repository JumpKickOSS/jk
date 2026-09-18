// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The components of a {@link BuildBlock}, mutable for the length of one {@link #with}: the one
 * spelling of the copy every {@code BuildBlock.with*} shares, so a new component is added here and in
 * the record once.
 */
final class BuildFields {

    /** {@code b} with {@code change} applied to a mutable copy of its components. */
    static BuildBlock with(BuildBlock b, Consumer<BuildFields> change) {
        BuildFields f = new BuildFields(b);
        change.accept(f);
        return f.build();
    }

    List<String> orderAfter;
    List<String> testPluginJars;
    boolean lint;
    DebugInfo debug;
    List<BuildBlock.KotlinPluginDecl> kotlinPlugins;
    List<String> kspOptions;
    JavacConfig javac;
    List<String> extraSrc;
    List<String> testExtraSrc;

    @Nullable
    String fixtures;

    @Nullable
    Integer testWorkers;

    List<String> testSerialTags;
    List<String> testIncludeTags, testExcludeTags;
    boolean testAssertions;
    boolean testCoverage;
    PlatformPolicy platformPolicy;
    UnmappedPolicy unmappedPolicy;
    PinPolicy pinPolicy;
    List<EnvDecl> testEnv;
    List<String> testTools;
    TestJvm testJvm;
    List<Sidecar> devSidecars;

    @Nullable
    DevReady devReady;

    List<BuildBlock.AuditIgnore> auditIgnores;
    EnvConfig env;

    BuildBlock.@Nullable BuildInfo buildInfo;
    BuildBlock.Dokka dokka;

    private BuildFields(BuildBlock b) {
        orderAfter = b.orderAfter();
        testPluginJars = b.testPluginJars();
        lint = b.lint();
        debug = b.debug();
        kotlinPlugins = b.kotlinPlugins();
        kspOptions = b.kspOptions();
        javac = b.javac();
        extraSrc = b.extraSrc();
        testExtraSrc = b.testExtraSrc();
        fixtures = b.fixtures();
        testWorkers = b.testWorkers();
        testSerialTags = b.testSerialTags();
        testIncludeTags = b.testIncludeTags();
        testExcludeTags = b.testExcludeTags();
        testAssertions = b.testAssertions();
        testCoverage = b.testCoverage();
        platformPolicy = b.platformPolicy();
        unmappedPolicy = b.unmappedPolicy();
        pinPolicy = b.pinPolicy();
        testEnv = b.testEnv();
        testTools = b.testTools();
        testJvm = b.testJvm();
        devSidecars = b.devSidecars();
        devReady = b.devReady();
        auditIgnores = b.auditIgnores();
        env = b.env();
        buildInfo = b.buildInfo();
        dokka = b.dokka();
    }

    BuildBlock build() {
        return new BuildBlock(
                orderAfter,
                testPluginJars,
                lint,
                debug,
                kotlinPlugins,
                kspOptions,
                javac,
                extraSrc,
                testExtraSrc,
                fixtures,
                testWorkers,
                testSerialTags,
                testIncludeTags,
                testExcludeTags,
                testAssertions,
                testCoverage,
                platformPolicy,
                unmappedPolicy,
                pinPolicy,
                testEnv,
                testTools,
                testJvm,
                devSidecars,
                devReady,
                auditIgnores,
                env,
                buildInfo,
                dokka);
    }
}
