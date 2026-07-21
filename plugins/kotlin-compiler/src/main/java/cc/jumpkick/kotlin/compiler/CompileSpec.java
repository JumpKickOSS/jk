// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import cc.jumpkick.plugin.protocol.PluginSpec;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-oriented Kotlin compile request ({@code KEY value}; blanks/{@code #} ignored; keys accumulate).
 * Policy-free: free-form flags arrive as {@code ARG}.
 */
final class CompileSpec {

    File outputDir;
    File workingDir; // null ⇒ non-incremental full compile
    File snapshotDir; // null ⇒ no classpath ABI snapshots
    String jvmTarget;
    String moduleName;
    String languageVersion;
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

    static CompileSpec from(PluginSpec spec) {
        CompileSpec s = new CompileSpec();
        var c = spec.config();
        if (spec.classesDir() != null) s.outputDir = spec.classesDir().toFile();
        if (spec.workdir() != null) s.workingDir = spec.workdir().toFile(); // present ⇒ incremental
        if (spec.snapshotDir() != null) s.snapshotDir = spec.snapshotDir().toFile();
        s.jvmTarget = c.stringOpt("jvmTarget").orElse(null);
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
        if (s.outputDir == null) throw new IllegalArgumentException("spec missing layout.classesDir (OUTPUT)");
        if (s.jvmTarget == null) throw new IllegalArgumentException("spec missing config jvmTarget");
        if (s.sources.isEmpty()) throw new IllegalArgumentException("spec has no source entries");
        return s;
    }
}
