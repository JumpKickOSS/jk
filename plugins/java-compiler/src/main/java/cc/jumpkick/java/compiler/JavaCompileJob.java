// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The facts every Zinc entry point needs, stated once: what to compile, against what, where the
 * classes and generated sources go, where the analysis lives, the release, the extra javac options,
 * the processor path, and which classpath entries come with their producer's Zinc analysis.
 * {@code workdir} may be null only for a forecast, which then answers "full" without an analysis to
 * read; {@code sourceOutput} is null when no processor generates sources.
 *
 * <p>{@code classpathAnalyses} maps a compile-classpath entry another jk compile produced — a
 * sibling's jar or classes dir, or this module's own main classes under a test compile — to that
 * compile's analysis file. It is a hint, never an input to the compile's identity: the engine keys
 * the compile on the entries' ABI, and a missing or stale analysis only costs precision.
 */
public record JavaCompileJob(
        List<Path> sources,
        List<Path> classpath,
        Path classOutput,
        @Nullable Path workdir,
        @Nullable Path sourceOutput,
        int release,
        List<String> extraOptions,
        List<Path> processorPath,
        @Nullable Path phasesLog,
        Map<Path, Path> classpathAnalyses) {

    /** A job that records no phase timings and knows no producer analyses. */
    public JavaCompileJob(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            @Nullable Path workdir,
            @Nullable Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath) {
        this(sources, classpath, classOutput, workdir, sourceOutput, release, extraOptions, processorPath, null);
    }

    /** A job that knows no producer analyses. */
    public JavaCompileJob(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            @Nullable Path workdir,
            @Nullable Path sourceOutput,
            int release,
            List<String> extraOptions,
            List<Path> processorPath,
            @Nullable Path phasesLog) {
        this(
                sources,
                classpath,
                classOutput,
                workdir,
                sourceOutput,
                release,
                extraOptions,
                processorPath,
                phasesLog,
                Map.of());
    }

    public JavaCompileJob {
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        extraOptions = List.copyOf(extraOptions);
        processorPath = List.copyOf(processorPath);
        classpathAnalyses = Map.copyOf(classpathAnalyses);
    }

    /** The same job with {@code classpathAnalyses} as its producer analyses. */
    public JavaCompileJob withClasspathAnalyses(Map<Path, Path> classpathAnalyses) {
        return new JavaCompileJob(
                sources,
                classpath,
                classOutput,
                workdir,
                sourceOutput,
                release,
                extraOptions,
                processorPath,
                phasesLog,
                classpathAnalyses);
    }
}
