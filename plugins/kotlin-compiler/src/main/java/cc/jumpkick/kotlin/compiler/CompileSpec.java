// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import cc.jumpkick.plugin.protocol.PluginSpec;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Line-oriented Kotlin compile request ({@code KEY value}; blanks/{@code #} ignored; keys accumulate).
 * Policy-free: free-form flags arrive as {@code ARG}.
 */
final class CompileSpec {

    final File outputDir;
    final String jvmTarget;

    @Nullable
    File workingDir; // null ⇒ non-incremental full compile

    @Nullable
    File snapshotDir; // null ⇒ no classpath ABI snapshots

    @Nullable
    String moduleName;

    @Nullable
    String languageVersion;

    @Nullable
    String apiVersion;

    final List<File> sources = new ArrayList<>();
    final List<File> classpath = new ArrayList<>();
    final List<File> friendPaths = new ArrayList<>();
    final List<String> extraArgs = new ArrayList<>();

    /** Parsed {@code PLUGIN id\tjar\topt=val...} lines — typed compiler plugins. */
    record Plugin(String id, File jar, List<String> options) {}

    final List<Plugin> plugins = new ArrayList<>();

    boolean incremental() {
        return workingDir != null;
    }

    private CompileSpec(File outputDir, String jvmTarget) {
        this.outputDir = outputDir;
        this.jvmTarget = jvmTarget;
    }

    static CompileSpec from(PluginSpec spec) {
        String jvmTarget = spec.requireCompileInputs();
        CompileSpec s =
                new CompileSpec(Objects.requireNonNull(spec.classesDir()).toFile(), jvmTarget);
        var c = spec.config();
        Path workdir = spec.workdir();
        if (workdir != null) s.workingDir = workdir.toFile(); // present ⇒ incremental
        Path snapshotDir = spec.snapshotDir();
        if (snapshotDir != null) s.snapshotDir = snapshotDir.toFile();
        s.moduleName = c.stringOpt("moduleName").orElse(null);
        s.languageVersion = c.stringOpt("languageVersion").orElse(null);
        s.apiVersion = c.stringOpt("apiVersion").orElse(null);
        for (Path p : spec.sources()) s.sources.add(p.toFile());
        for (Path p : spec.compileClasspath()) s.classpath.add(p.toFile());
        for (Path p : spec.friendPaths()) s.friendPaths.add(p.toFile());
        s.extraArgs.addAll(spec.args());
        for (PluginSpec.CompilerPlugin cp : spec.compilerPlugins()) {
            s.plugins.add(new Plugin(cp.id(), cp.jar().toFile(), cp.options()));
        }
        return s;
    }
}
