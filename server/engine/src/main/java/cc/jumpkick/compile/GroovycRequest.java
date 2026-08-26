// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.jdk.SupportedJdk;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import lombok.Builder;

/**
 * Input to {@link WorkerCompileDriver}'s Groovy arm (forks {@code jk-groovy-compiler}). Unlike
 * {@link KotlincRequest} there is no {@code javaHome}: groovyc takes no project JDK.
 * {@code workerClasspath} is
 * the plugin jar + Groovy runtime. {@code sources} may mix {@code .groovy} and {@code .java} —
 * any Java (also via {@code javaSourceRoots}) selects joint mode: stubs land in {@code stubsOut}
 * (null ⇒ not retained) and javac class output is discarded under {@code workDir} (null ⇒ worker
 * temp). jk's javac step owns the real Java outputs.
 */
@Builder
public record GroovycRequest(
        List<Path> sources,
        List<Path> javaSourceRoots,
        List<Path> classpath,
        List<Path> processorPath,
        Path outputDir,
        Path stubsOut,
        int jvmTarget,
        List<Path> workerClasspath,
        Path workDir,
        List<String> extraArgs) {

    public GroovycRequest {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(workerClasspath, "workerClasspath");
        sources = List.copyOf(sources);
        javaSourceRoots = javaSourceRoots == null ? List.of() : List.copyOf(javaSourceRoots);
        classpath = List.copyOf(classpath);
        processorPath = processorPath == null ? List.of() : List.copyOf(processorPath);
        workerClasspath = List.copyOf(workerClasspath);
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        if (jvmTarget < SupportedJdk.MIN_MAJOR) {
            throw new IllegalArgumentException(
                    "jvmTarget must be >= " + SupportedJdk.MIN_MAJOR + ", got: " + jvmTarget);
        }
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("workerClasspath must include the worker jar + Groovy closure");
        }
    }

    public static class GroovycRequestBuilder {
        private List<Path> sources = List.of();
        private List<Path> javaSourceRoots = List.of();
        private List<Path> classpath = List.of();
        private List<Path> processorPath = List.of();
        private int jvmTarget = 21;
        private List<Path> workerClasspath = List.of();
        private List<String> extraArgs = List.of();
    }
}
