// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The components of a {@link JkBuild.Build}, mutable for the length of one {@code with*} copy. */
final class BuildFields {
    List<String> orderAfter;
    List<String> testPluginJars;
    boolean lint;
    DebugInfo debug;
    List<JkBuild.KotlinPluginDecl> kotlinPlugins;
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
    List<Sidecar> devSidecars;

    @Nullable
    DevReady devReady;

    List<JkBuild.AuditIgnore> auditIgnores;
    EnvConfig env;

    BuildFields(JkBuild.Build b) {
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
        devSidecars = b.devSidecars();
        devReady = b.devReady();
        auditIgnores = b.auditIgnores();
        env = b.env();
    }

    JkBuild.Build build() {
        return new JkBuild.Build(
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
                devSidecars,
                devReady,
                auditIgnores,
                env);
    }
}
