// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.JavaRelease;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import lombok.Builder;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

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
        @Nullable Path stubsOut,
        int jvmTarget,
        List<Path> workerClasspath,
        @Nullable Path workDir,
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
        if (jvmTarget < JavaRelease.OLDEST) {
            throw new IllegalArgumentException("jvmTarget must be >= " + JavaRelease.OLDEST + ", got: " + jvmTarget);
        }
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("workerClasspath must include the worker jar + Groovy closure");
        }
    }

    /**
     * Lombok fills the staged fields in; the class is declared here only to give the collection and
     * scalar defaults. Unmarked because those generated fields are write-once builder state, not the
     * record's contract.
     */
    @NullUnmarked
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
