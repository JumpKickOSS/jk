// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Linking;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.CompilerProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jetbrains.kotlin.buildtools.api.CompilationResult;
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy;
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains;
import org.jetbrains.kotlin.buildtools.api.SourcesChanges;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration.Builder;
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation;

/**
 * Child-JVM entry point that drives the Kotlin Build Tools API.
 *
 * <p>jk launches this as {@code java -cp <worker.jar>:<kotlin-bta-closure>
 * cc.jumpkick.kotlin.compiler.KotlinCompiler @&lt;spec&gt;}. The plugin reads the {@link
 * CompileSpec}, runs an in-process JVM compile (incremental when the spec carries a {@code
 * WORKDIR}), streams diagnostics back as JSONL, and exits {@link Exit#SUCCESS},
 * {@link Exit#FAILURE} on a compilation error, {@link CompilerProtocol#COMPILER_FAULT} on an
 * OOM/internal compiler error, or {@link Exit#SOFTWARE} for a bad spec / unexpected failure.
 *
 * <p>It uses the {@link KotlinToolchains} entry point (the post-2.4 BTA surface; the older {@code
 * CompilationService} flow is deprecated). It depends on nothing but the Build Tools API at compile
 * time — the implementation and the Kotlin compiler arrive on the classpath at runtime,
 * version-matched by jk, so the plugin never leaks compiler deps into jk.
 */
public final class KotlinCompiler implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-kotlin-compiler", "##JKKC:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        return CompilerProtocol.compileFromSpec(
                manifest().id(), args, out, (spec, proto) -> compile(CompileSpec.from(spec), proto));
    }

    static int compile(CompileSpec spec, CompilerProtocol proto) throws Exception {
        spec.outputDir.mkdirs();

        KotlinToolchains toolchains = KotlinToolchains.loadImplementation(KotlinCompiler.class.getClassLoader());
        JvmPlatformToolchain jvm = JvmPlatformToolchain.from(toolchains);
        List<Path> sources = spec.sources.stream().map(File::toPath).toList();

        CompilationResult result;
        try (KotlinToolchains.BuildSession session = toolchains.createBuildSession()) {
            ExecutionPolicy policy = toolchains.createInProcessExecutionPolicy();
            KcLogger logger = new KcLogger(proto);

            JvmCompilationOperation.Builder op = jvm.jvmCompilationOperationBuilder(sources, spec.outputDir.toPath());
            // Destination is a first-class builder param above; everything else jk
            // controls as raw kotlinc arguments parsed into the typed argument model.
            op.getCompilerArguments().applyArgumentStrings(buildArgs(spec));
            if (!spec.plugins.isEmpty()) {
                // Compiler plugins must go through the typed COMPILER_PLUGINS argument —
                // raw -Xplugin/-P strings are silently ignored by the BTA execution path.
                List<org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin> plugins = new ArrayList<>();
                for (CompileSpec.Plugin plugin : spec.plugins) {
                    List<org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption> options =
                            new ArrayList<>();
                    for (String opt : plugin.options()) {
                        int eq = opt.indexOf('=');
                        options.add(new org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption(
                                eq < 0 ? opt : opt.substring(0, eq), eq < 0 ? "" : opt.substring(eq + 1)));
                    }
                    plugins.add(new org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin(
                            plugin.id(), List.of(jarSuffixed(plugin.jar().toPath())), options, Set.of()));
                }
                op.getCompilerArguments()
                        .set(
                                org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments.COMPILER_PLUGINS,
                                plugins);
            }

            File workingDir = spec.workingDir; // present ⇒ incremental
            if (workingDir != null) {
                workingDir.mkdirs();
                // Classpath ABI snapshots let BTA recompile precisely when a
                // dependency changes (without them it would fall back to a full
                // rebuild on any classpath change). Source edits are tracked by
                // the working dir under SourcesChanges.ToBeCalculated regardless.
                File snapshotDir = spec.snapshotDir;
                List<Path> depSnapshots = snapshotDir != null
                        ? snapshotClasspath(spec.classpath, snapshotDir.toPath(), (entry, out) -> {
                            org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmClasspathSnapshottingOperation
                                    snapOp = jvm.classpathSnapshottingOperationBuilder(entry)
                                            .build();
                            session.executeOperation(snapOp, policy, logger).saveSnapshot(out);
                        })
                        : List.of();
                Builder ic = op.snapshotBasedIcConfigurationBuilder(
                        workingDir.toPath(), SourcesChanges.ToBeCalculated.INSTANCE, depSnapshots);
                ic.set(JvmSnapshotBasedIncrementalCompilationConfiguration.USE_FIR_RUNNER, Boolean.TRUE);
                op.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, ic.build());
            }

            result = session.executeOperation(op.build(), policy, logger);
        }

        proto.result(result.name());
        return switch (result) {
            case COMPILATION_SUCCESS -> Exit.SUCCESS;
            case COMPILATION_ERROR -> Exit.FAILURE;
            default -> CompilerProtocol.COMPILER_FAULT; // COMPILATION_OOM_ERROR, COMPILER_INTERNAL_ERROR
        };
    }

    /** Writes one classpath entry's ABI snapshot to {@code out}; the BTA operation in production. */
    @FunctionalInterface
    interface Snapshotter {
        void snapshot(Path entry, Path out) throws Exception;
    }

    private static final String SNAPSHOT_SUFFIX = ".snapshot";

    /**
     * Compute (and cache) a classpath ABI snapshot file per compile-classpath entry, returning the
     * snapshot file list for the IC config. A snapshot is named by the entry's path and its
     * current shape: a jar's size and mtime, a directory's listing (relative path, size, mtime of
     * every file). A store jar never changes, so its snapshot is reused across builds; a sibling
     * module's {@code target/classes/main} is rewritten at the same path by every build, and its
     * snapshot is recomputed exactly then — which is what lets the incremental compile see that a
     * dependency's API moved and recompile the sources that use it. Only the current snapshot of
     * an entry is kept, so the shared snapshot cache stays bounded. On any failure the compile runs
     * with no snapshots and stays source-incremental.
     */
    static List<Path> snapshotClasspath(List<File> classpath, Path dir, Snapshotter snapshotter) {
        try {
            Files.createDirectories(dir);
            List<Path> out = new ArrayList<>(classpath.size());
            for (File entry : classpath) {
                if (!entry.exists()) continue;
                String prefix = Hashing.sha256Hex(entry.getAbsolutePath()) + "-";
                Path snapshot = dir.resolve(prefix + shapeDigest(entry.toPath()) + SNAPSHOT_SUFFIX);
                if (!Files.isRegularFile(snapshot)) {
                    dropStale(dir, prefix);
                    // Written beside and moved in whole: a concurrent compile of another module
                    // reading the same entry sees either no snapshot or a complete one.
                    Path staging = dir.resolve(prefix + UUID.randomUUID() + ".tmp");
                    try {
                        snapshotter.snapshot(entry.toPath(), staging);
                        Files.move(
                                staging, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(staging);
                    }
                }
                out.add(snapshot);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * A digest of what a classpath entry looks like right now: for a jar its size and mtime, for a
     * classes directory the sorted (relative path, size, mtime) of every file in it. Cheap to
     * compute against snapshotting, which reads every class file, and changes whenever a compiler
     * rewrote, added or deleted a class.
     */
    private static String shapeDigest(Path entry) throws IOException {
        MessageDigest digest = Hashing.newSha256();
        if (Files.isDirectory(entry)) {
            List<String> listing = new ArrayList<>();
            PathUtil.forEachRegularFile(
                    entry,
                    (file, attrs) -> listing.add(
                            entry.relativize(file).toString().replace(File.separatorChar, '/') + shape(attrs)));
            Collections.sort(listing);
            for (String line : listing) digest.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        } else {
            digest.update(shape(Files.readAttributes(entry, BasicFileAttributes.class))
                    .getBytes(StandardCharsets.UTF_8));
        }
        return Hashing.hex(digest.digest());
    }

    private static String shape(BasicFileAttributes attrs) {
        return "\t" + attrs.size() + "\t" + attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);
    }

    /** Remove the snapshots of an entry that describe a shape it no longer has. */
    private static void dropStale(Path dir, String prefix) throws IOException {
        List<Path> stale = new ArrayList<>();
        PathUtil.forEachChild(dir, (file, attrs) -> {
            String name = file.getFileName().toString();
            if (name.startsWith(prefix) && name.endsWith(SNAPSHOT_SUFFIX)) stale.add(file);
            return true;
        });
        for (Path file : stale) Files.deleteIfExists(file);
    }

    /**
     * Translate the spec into raw Kotlin compiler arguments (destination excluded — it's a builder
     * parameter). The plugin sets only what the spec describes; jk owns all policy (e.g. {@code
     * -no-stdlib}) via {@code ARG} entries appended verbatim at the end.
     */
    static List<String> buildArgs(CompileSpec spec) {
        List<String> args = new ArrayList<>();
        args.add("-jvm-target");
        args.add(spec.jvmTarget);
        if (spec.moduleName != null) {
            args.add("-module-name");
            args.add(spec.moduleName);
        }
        if (spec.languageVersion != null) {
            args.add("-language-version");
            args.add(spec.languageVersion);
        }
        if (spec.apiVersion != null) {
            args.add("-api-version");
            args.add(spec.apiVersion);
        }
        if (!spec.classpath.isEmpty()) {
            args.add("-classpath");
            args.add(Classpaths.join(spec.classpath.stream().map(File::toPath).toList()));
        }
        if (!spec.friendPaths.isEmpty()) {
            args.add("-Xfriend-paths=" + joinCommas(spec.friendPaths));
        }
        if (spec.incremental()) {
            // Required whenever the FIR (K2) incremental runner is selected.
            args.add("-Xuse-fir-ic");
        }
        args.addAll(spec.extraArgs);
        return args;
    }

    /**
     * The compiler's plugin loader silently ignores classpath entries that don't end in
     * {@code .jar}. Alias any extensionless path so the plugin is actually loaded.
     */
    private static Path jarSuffixed(Path jar) throws IOException {
        if (jar.getFileName().toString().endsWith(".jar")) return jar;
        // Unique directory name without creating a file first.
        Path dir = Files.createTempDirectory("jk-kotlin-plugin-");
        dir.toFile().deleteOnExit();
        Path suffixed = dir.resolve(jar.getFileName() + ".jar");
        Linking.linkOrCopy(jar, suffixed);
        suffixed.toFile().deleteOnExit();
        return suffixed;
    }

    /** Comma-joined, for {@code -Xfriend-paths}: not a classpath, so not {@link Classpaths}. */
    private static String joinCommas(List<File> files) {
        return files.stream().map(File::getAbsolutePath).collect(Collectors.joining(","));
    }
}
