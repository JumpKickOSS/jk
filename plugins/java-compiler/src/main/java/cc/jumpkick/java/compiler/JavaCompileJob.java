// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The eight facts every Zinc entry point needs, stated once: what to compile, against what, where
 * the classes and generated sources go, where the analysis lives, the release, the extra javac
 * options and the processor path. {@code workdir} may be null only for a forecast, which then
 * answers "full" without an analysis to read; {@code sourceOutput} is null when no processor
 * generates sources.
 */
public record JavaCompileJob(
        List<Path> sources,
        List<Path> classpath,
        Path classOutput,
        @Nullable Path workdir,
        @Nullable Path sourceOutput,
        int release,
        List<String> extraOptions,
        List<Path> processorPath) {

    public JavaCompileJob {
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        extraOptions = List.copyOf(extraOptions);
        processorPath = List.copyOf(processorPath);
    }
}
