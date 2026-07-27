// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy.compiler;

import cc.jumpkick.plugin.protocol.PluginSpec;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Groovy compile request decoded from the unified JSONL plugin spec. Sources mix {@code .groovy}
 * and (joint mode) {@code .java}; free-form groovyc flags arrive as {@code ARG}. Policy-free.
 */
final class CompileSpec {

    File outputDir;
    File workDir; // null ⇒ worker-managed temp scratch (discard javac output, temp stubs)
    File stubsOut; // null ⇒ joint-mode stubs go to a temp dir and are not retained
    String jvmTarget;
    final List<File> sources = new ArrayList<>();
    final List<File> classpath = new ArrayList<>();
    final List<File> processorPath = new ArrayList<>();
    final List<File> javaSourceRoots = new ArrayList<>();
    final List<String> extraArgs = new ArrayList<>();

    /** Joint mode ⇔ any listed source is a {@code .java} file. */
    boolean joint() {
        return sources.stream().anyMatch(CompileSpec::isJava);
    }

    List<File> javaSources() {
        return sources.stream().filter(CompileSpec::isJava).toList();
    }

    List<File> groovySources() {
        return sources.stream().filter(f -> !isJava(f)).toList();
    }

    static boolean isJava(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    static CompileSpec from(PluginSpec spec) {
        CompileSpec s = new CompileSpec();
        var c = spec.config();
        if (spec.classesDir() != null) s.outputDir = spec.classesDir().toFile();
        if (spec.workdir() != null) s.workDir = spec.workdir().toFile();
        spec.extra("stubsOut").ifPresent(p -> s.stubsOut = p.toFile());
        s.jvmTarget = c.stringOpt("jvmTarget").orElse(null);
        for (String root : c.stringList("javaSourceRoots")) s.javaSourceRoots.add(new File(root));
        for (Path p : spec.sources()) s.sources.add(p.toFile());
        for (Path p : spec.compileClasspath()) s.classpath.add(p.toFile());
        for (Path p : spec.processorClasspath()) s.processorPath.add(p.toFile());
        s.extraArgs.addAll(spec.args());
        if (s.outputDir == null) throw new IllegalArgumentException("spec missing layout.classesDir (OUTPUT)");
        if (s.jvmTarget == null) throw new IllegalArgumentException("spec missing config jvmTarget");
        if (s.sources.isEmpty()) throw new IllegalArgumentException("spec has no source entries");
        return s;
    }
}
