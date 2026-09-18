// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Optional {@code [build]} block: order-only deps, test plugin jars, lint, debug info, Kotlin
 * plugins, KSP options, javac plugins, extra source roots, and per-module test worker pin —
 * never on a classpath or lockfile.
 */
public record BuildBlock(
        List<String> orderAfter,
        List<String> testPluginJars,
        boolean lint,
        /** {@code [build] debug}: the debug information every javac compile writes. Default {@link DebugInfo#FULL}. */
        DebugInfo debug,
        List<KotlinPluginDecl> kotlinPlugins,
        List<String> kspOptions,
        /** {@code [javac]}: the javac plugins compile-main and compile-test invoke, and verbatim args. */
        JavacConfig javac,
        List<String> extraSrc,
        /**
         * {@code [test] extra-src}: extra module-relative source roots (or single files) compiled
         * with the test tier into test classes. The test-scoped twin of {@code extraSrc}. Use
         * {@link #fixtures} for a sibling-consumed helper source set.
         */
        List<String> testExtraSrc,
        /**
         * {@code [test] fixtures}: module-relative source root compiled by
         * {@code compile-test-fixtures} into a directory that is never an artifact. {@code null}
         * means none. Boolean {@code true} in the manifest stores {@link #DEFAULT_FIXTURES}.
         */
        @Nullable String fixtures,
        /** {@code [build] test-workers}: {@code null} = inherit CLI/auto; {@code 0} = auto; {@code 1} = serial. */
        @Nullable Integer testWorkers,
        /**
         * {@code [test] serial-tags}: class-level JUnit tags whose classes never share the
         * sharded worker pool — they run in a single trailing worker while untagged classes
         * shard across {@code workers}. Lets a module keep {@code workers = 0} for its unit
         * tier while its nested-engine/integration classes stay serial.
         */
        List<String> testSerialTags,
        /** {@code [test] include-tags}: the baseline tags a bare {@code jk test} runs; empty means every tag. */
        List<String> testIncludeTags,
        /** {@code [test] exclude-tags}: the baseline tags a bare {@code jk test} leaves out. */
        List<String> testExcludeTags,
        /**
         * {@code [test] assertions}: whether every forked test JVM runs with {@code -ea}, as
         * Surefire's and Gradle's do. Default {@code true}; {@code false} runs the suite with Java
         * and Kotlin {@code assert} statements disabled.
         */
        boolean testAssertions,
        /**
         * {@code [test] coverage}: whether every test run of this module is a coverage run —
         * the JaCoCo agent on each forked test JVM and the module's report written — without
         * {@code --coverage} on the command line. Default {@code false}.
         */
        boolean testCoverage,
        /**
         * {@code [resolve] platform}: how BOM managed pins constrain the graph. Default
         * {@link PlatformPolicy#ENFORCED}.
         */
        PlatformPolicy platformPolicy,
        /**
         * {@code [resolve] unmapped}: how bare fills for GAs the platform does NOT manage are
         * constrained. Default {@link UnmappedPolicy#MEDIATE}.
         */
        UnmappedPolicy unmappedPolicy,
        /**
         * {@code [resolve] pins}: how the project's own exact pin meets a transitive's constraint
         * on the same module. Default {@link PinPolicy#EXACT}.
         */
        PinPolicy pinPolicy,
        /**
         * {@code [test] env} — what every forked test JVM's environment gets, in the order the
         * manifest lists it. Test-scoped like {@code testPluginJars}, hence its home here.
         *
         * <p>A list rather than a map because the two things a module says about the environment
         * are different statements — {@link EnvDecl.Forward} names a variable it wants if the
         * caller has one, {@link EnvDecl.Set} states a value outright — and because order is
         * then a rule the manifest can express instead of one a reader has to memorise: later
         * wins, top to bottom.
         */
        List<EnvDecl> testEnv,
        /**
         * {@code [test] tools} — the external executables the suite shells out to ({@code node},
         * {@code git}, {@code protoc}), by the name the tests invoke them under. Each one's
         * identity — where it resolves on the PATH the test JVM gets, and what its
         * {@code --version} says — is a run-tests input, so upgrading the tool re-runs the tests
         * that depend on it. Test-scoped like {@code testEnv}, hence its home here.
         */
        List<String> testTools,
        /**
         * {@code [test] jvm-args} and {@code [test] system-properties} — what every forked
         * test JVM's command line carries beyond jk's own tuning. Test-scoped like {@code
         * testEnv}, hence its home here.
         */
        TestJvm testJvm,
        /**
         * {@code [dev.sidecars]} — processes {@code jk dev} runs beside the application (a
         * frontend dev server, a docs server), in manifest order. Dev-only: {@code jk run},
         * {@code jk build}, and {@code jk test} never read it, and nothing here enters an action
         * key. Not a build input, like {@code [test]}, hence its home here.
         */
        List<Sidecar> devSidecars,
        /**
         * {@code [dev] ready} / {@code ready-pattern} / {@code ready-timeout} — the probe that
         * says the application itself is listening under {@code jk dev}; null means none, and
         * the app counts as ready once forked. Dev-only, like {@code devSidecars}.
         */
        @Nullable DevReady devReady,
        /**
         * {@code [audit] ignore} — advisories {@code jk audit} reports but does not gate on,
         * each with its reason and an optional expiry date. Read by the audit alone; never an
         * action-key input.
         */
        List<AuditIgnore> auditIgnores,
        /**
         * {@code [env]} — what this module's workers may take from the environment beyond the
         * allow-list, and whether they inherit all of it. Per module, like {@code [test]}.
         */
        EnvConfig env,
        /**
         * {@code [build-info]} — the git build-info resources the module's jar carries. {@code
         * null} when the table is absent: nothing is written.
         */
        @Nullable BuildInfo buildInfo,
        /** {@code [dokka]} — Dokka's version and output format for a Kotlin or mixed module's javadoc jar. */
        Dokka dokka) {

    /** Default {@code [test] fixtures = true} root — {@code src/fixtures/java}. */
    public static final String DEFAULT_FIXTURES = "src/fixtures/java";

    public static final BuildBlock EMPTY = new BuildBlock(
            List.of(),
            List.of(),
            true,
            DebugInfo.FULL,
            List.of(),
            List.of(),
            JavacConfig.EMPTY,
            List.of(),
            List.of(),
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            true,
            false,
            PlatformPolicy.ENFORCED,
            UnmappedPolicy.MEDIATE,
            PinPolicy.EXACT,
            List.of(),
            List.of(),
            TestJvm.EMPTY,
            List.of(),
            null,
            List.of(),
            EnvConfig.EMPTY,
            null,
            Dokka.DEFAULT);

    public BuildBlock {
        orderAfter = orderAfter == null ? List.of() : List.copyOf(orderAfter);
        testPluginJars = testPluginJars == null ? List.of() : List.copyOf(testPluginJars);
        debug = debug == null ? DebugInfo.FULL : debug;
        kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
        kspOptions = kspOptions == null ? List.of() : List.copyOf(kspOptions);
        javac = javac == null ? JavacConfig.EMPTY : javac;
        extraSrc = extraSrc == null ? List.of() : List.copyOf(new LinkedHashSet<>(extraSrc));
        testExtraSrc = testExtraSrc == null ? List.of() : List.copyOf(testExtraSrc);
        if (fixtures != null && fixtures.isBlank()) fixtures = null;
        if (testWorkers != null && testWorkers < 0) testWorkers = 0;
        testSerialTags = testSerialTags == null ? List.of() : List.copyOf(testSerialTags);
        testIncludeTags = testIncludeTags == null ? List.of() : List.copyOf(testIncludeTags);
        testExcludeTags = testExcludeTags == null ? List.of() : List.copyOf(testExcludeTags);
        platformPolicy = platformPolicy == null ? PlatformPolicy.ENFORCED : platformPolicy;
        unmappedPolicy = unmappedPolicy == null ? UnmappedPolicy.MEDIATE : unmappedPolicy;
        pinPolicy = pinPolicy == null ? PinPolicy.EXACT : pinPolicy;
        testEnv = testEnv == null ? List.of() : List.copyOf(testEnv);
        testTools = testTools == null ? List.of() : List.copyOf(testTools);
        testJvm = testJvm == null ? TestJvm.EMPTY : testJvm;
        devSidecars = devSidecars == null ? List.of() : List.copyOf(devSidecars);
        auditIgnores = auditIgnores == null ? List.of() : List.copyOf(auditIgnores);
        env = env == null ? EnvConfig.EMPTY : env;
        dokka = dokka == null ? Dokka.DEFAULT : dokka;
    }

    /**
     * What every forked test JVM's environment is declared to get, in precedence order:
     * {@code [env] vars} for every worker of the module, then {@code [test] env} on top.
     */
    public List<EnvDecl> testEnvDecls() {
        if (env.vars().isEmpty()) return testEnv;
        List<EnvDecl> all = new ArrayList<>(env.vars());
        all.addAll(testEnv);
        return List.copyOf(all);
    }

    /** True when this module declares a fixtures source root. */
    public boolean hasFixtures() {
        return fixtures != null;
    }

    /** Append {@code dirs} to {@code extra-src} (variant fold point). */
    public BuildBlock withExtraSrc(List<String> dirs) {
        if (dirs.isEmpty()) return this;
        var all = new ArrayList<>(extraSrc);
        all.addAll(dirs);
        return with(f -> f.extraSrc = all);
    }

    /** Append {@code dirs} to {@code [test] extra-src}. */
    public BuildBlock withTestExtraSrc(List<String> dirs) {
        if (dirs.isEmpty()) return this;
        var all = new ArrayList<>(testExtraSrc);
        all.addAll(dirs);
        return with(f -> f.testExtraSrc = all);
    }

    public BuildBlock withPlatformPolicy(PlatformPolicy policy) {
        return with(f -> f.platformPolicy = policy == null ? PlatformPolicy.ENFORCED : policy);
    }

    /** The same block with {@code [resolve] pins} set. */
    public BuildBlock withPinPolicy(PinPolicy policy) {
        return with(f -> f.pinPolicy = policy == null ? PinPolicy.EXACT : policy);
    }

    /** The same block with {@code [[kotlin-plugins]]} set. */
    public BuildBlock withKotlinPlugins(List<KotlinPluginDecl> plugins) {
        return with(f -> f.kotlinPlugins = plugins);
    }

    /** The same block with {@code [test] include-tags} / {@code exclude-tags} set. */
    public BuildBlock withTestTags(List<String> includeTags, List<String> excludeTags) {
        return with(f -> {
            f.testIncludeTags = includeTags;
            f.testExcludeTags = excludeTags;
        });
    }

    /** The same block with {@code [test] env} set. */
    public BuildBlock withTestEnv(List<EnvDecl> decls) {
        return with(f -> f.testEnv = decls);
    }

    /** The same block with {@code [test] tools} set. */
    public BuildBlock withTestTools(List<String> tools) {
        return with(f -> f.testTools = tools);
    }

    /** The same block with {@code [test] jvm-args} / {@code system-properties} set. */
    public BuildBlock withTestJvm(TestJvm jvm) {
        return with(f -> f.testJvm = jvm);
    }

    /** The same block with {@code [dev.sidecars]} set. */
    public BuildBlock withDevSidecars(List<Sidecar> sidecars) {
        return with(f -> f.devSidecars = sidecars);
    }

    /** The same block with the {@code [dev]} probe of the application set. */
    public BuildBlock withDevReady(@Nullable DevReady ready) {
        return with(f -> f.devReady = ready);
    }

    /** The same block with {@code [javac]} set. */
    public BuildBlock withJavac(JavacConfig config) {
        return with(f -> f.javac = config);
    }

    /** The same block with {@code [env]} set. */
    public BuildBlock withEnv(EnvConfig config) {
        return with(f -> f.env = config);
    }

    /** The same block with {@code [audit] ignore} set. */
    public BuildBlock withAuditIgnores(List<AuditIgnore> ignores) {
        return with(f -> f.auditIgnores = ignores);
    }

    /** The same block with the {@code [build-info]} table set. */
    public BuildBlock withBuildInfo(@Nullable BuildInfo info) {
        return with(f -> f.buildInfo = info);
    }

    /** The same block with the {@code [dokka]} table set. */
    public BuildBlock withDokka(Dokka dokka) {
        return with(f -> f.dokka = dokka);
    }

    /** One component changed, the rest copied — the one spelling of the copy every {@code with*} shares. */
    private BuildBlock with(Consumer<BuildFields> change) {
        return BuildFields.with(this, change);
    }

    /**
     * Effective test-worker request for this module: a positive module pin wins (hermetic
     * opt-out); otherwise the request's value — the build's resolved auto share, or {@code 0}
     * for a single-project build's own auto. A pin of {@code 0} is the same as no pin: it says
     * "auto", and auto is the share, not the whole machine — a module must not escape the
     * budget the rest of the build is sharing by spelling the default out loud.
     */
    public int effectiveTestWorkers(int cliOrGlobal) {
        if (testWorkers != null && testWorkers > 0) return testWorkers;
        return Math.max(0, cliOrGlobal);
    }

    /** {@code orderAfter} plus every {@code testPluginJars} module, de-duplicated. */
    public List<String> allOrderAfter() {
        if (testPluginJars.isEmpty()) return orderAfter;
        var all = new LinkedHashSet<>(orderAfter);
        all.addAll(testPluginJars);
        return List.copyOf(all);
    }

    /**
     * {@code [dokka]}: how a Kotlin or mixed module's javadoc jar is documented. Dokka runs at
     * {@code version} — the manifest's selector, a pin by default — and writes {@code format}:
     * javadoc-shaped HTML, which is what Maven Central's javadoc jar convention expects, or Dokka's
     * own HTML. A pure Java module never reads this table.
     */
    public record Dokka(VersionSelector version, Format format) {

        /** The output Dokka writes into the javadoc jar. */
        public enum Format {
            JAVADOC,
            HTML;

            public String wireName() {
                return name().toLowerCase(Locale.ROOT);
            }

            /** Parse {@code javadoc} / {@code html} (case-insensitive). */
            public static Format parse(String raw) {
                return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                    case "javadoc" -> JAVADOC;
                    case "html" -> HTML;
                    default ->
                        throw new IllegalArgumentException("unknown dokka format `" + raw + "` (want javadoc or html)");
                };
            }
        }

        /** The Dokka release a module gets when the table names none. */
        public static final String DEFAULT_VERSION = "2.2.0";

        /** The table at its defaults: {@link #DEFAULT_VERSION}, javadoc format. */
        public static final Dokka DEFAULT =
                new Dokka(new VersionSelector.Exact(DEFAULT_VERSION, DEFAULT_VERSION), Format.JAVADOC);

        public Dokka {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(format, "format");
        }

        public boolean isDefault() {
            return equals(DEFAULT);
        }
    }

    /**
     * {@code [build-info]}: the module's jar carries {@code git.properties} — the keys Spring Boot's
     * {@code GitProperties} and the git-commit-id plugins' consumers read — and, when the module is
     * a Spring Boot application, {@code META-INF/build-info.properties} with Boot's {@code build.*}
     * keys. Both are written into the classes tree as cached resources, so every jar shape (thin,
     * fat, Boot) carries them.
     *
     * @param file the resource path of the git properties file inside the jar, default {@code
     *     git.properties}
     * @param buildTime {@code time = "build"}: the {@code git.build.time} and {@code build.time}
     *     keys read the wall clock at build time, so every build regenerates the file and
     *     repackages the jar. The default reads the commit time, which keeps one commit's builds
     *     byte-identical.
     */
    public record BuildInfo(String file, boolean buildTime) {

        public static final String DEFAULT_FILE = "git.properties";

        /** The table with every key at its default. */
        public static final BuildInfo DEFAULT = new BuildInfo(DEFAULT_FILE, false);

        public BuildInfo {
            file = file == null || file.isBlank() ? DEFAULT_FILE : file;
        }
    }

    /**
     * One {@code [audit] ignore} entry: the advisory {@code id} ({@code GHSA-…}, {@code CVE-…}) the
     * audit reports without gating on, why, and — when {@code until} is set — the last day that
     * holds. From the day after, the entry is expired: the finding gates again and the report says
     * so.
     */
    public record AuditIgnore(
            String id, String reason, @Nullable LocalDate until) {
        /** True once {@code today} is past {@code until}; an entry without a date never expires. */
        public boolean expiredOn(LocalDate today) {
            return until != null && today.isAfter(until);
        }
    }

    /**
     * {@code [[kotlin-plugins]]} entry: {@code group:artifact[:version]} (omit version to match
     * the project Kotlin version); {@code id} defaults to the artifact name.
     */
    public record KotlinPluginDecl(String id, String coordinate, List<String> options) {
        public KotlinPluginDecl {
            Objects.requireNonNull(coordinate, "coordinate");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
