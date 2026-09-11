// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy.compiler;

import cc.jumpkick.plugin.protocol.PluginSpec;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Groovy compile request decoded from the unified JSONL plugin spec. Sources mix {@code .groovy}
 * and (joint mode) {@code .java}; free-form groovyc flags arrive as {@code ARG}. Policy-free.
 */
final class CompileSpec {

    final File outputDir;
    final String jvmTarget;

    @Nullable
    File workDir; // null ⇒ worker-managed temp scratch (discard javac output, temp stubs)

    @Nullable
    File stubsOut; // null ⇒ joint-mode stubs go to a temp dir and are not retained

    final List<File> sources = new ArrayList<>();
    final List<File> classpath = new ArrayList<>();
    final List<File> processorPath = new ArrayList<>();
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

    private CompileSpec(File outputDir, String jvmTarget) {
        this.outputDir = outputDir;
        this.jvmTarget = jvmTarget;
    }

    static CompileSpec from(PluginSpec spec) {
        String jvmTarget = spec.requireCompileInputs();
        CompileSpec s =
                new CompileSpec(Objects.requireNonNull(spec.classesDir()).toFile(), jvmTarget);
        Path workdir = spec.workdir();
        if (workdir != null) s.workDir = workdir.toFile();
        spec.extra("stubsOut").ifPresent(p -> s.stubsOut = p.toFile());
        for (Path p : spec.sources()) s.sources.add(p.toFile());
        for (Path p : spec.compileClasspath()) s.classpath.add(p.toFile());
        for (Path p : spec.processorClasspath()) s.processorPath.add(p.toFile());
        s.extraArgs.addAll(spec.args());
        return s;
    }
}
