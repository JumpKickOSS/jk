// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.jdk.SupportedJdk;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@link GroovycDriver} (forks {@code jk-groovy-compiler}). {@code workerClasspath} is
 * the plugin jar + Groovy runtime closure. {@code sources} may mix {@code.groovy} and {@code
 * .java} — any Java presence (also via {@code javaSourceRoots}) selects joint mode, where the Java
 * sources serve resolution only: stubs land in {@code stubsOut} (null ⇒ not retained) and javac's
 * class output is discarded under {@code workDir} (null ⇒ worker temp) — jk's javac step owns the
 * real Java outputs.
 */
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

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<Path> sources = List.of();
        private List<Path> javaSourceRoots = List.of();
        private List<Path> classpath = List.of();
        private List<Path> processorPath = List.of();
        private Path outputDir;
        private Path stubsOut;
        private int jvmTarget = 21;
        private List<Path> workerClasspath = List.of();
        private Path workDir;
        private List<String> extraArgs = List.of();

        public Builder sources(List<Path> v) {
            this.sources = v;
            return this;
        }

        public Builder javaSourceRoots(List<Path> v) {
            this.javaSourceRoots = v;
            return this;
        }

        public Builder classpath(List<Path> v) {
            this.classpath = v;
            return this;
        }

        /** Annotation-processor classpath for the joint-mode javac sweep. */
        public Builder processorPath(List<Path> v) {
            this.processorPath = v;
            return this;
        }

        public Builder outputDir(Path v) {
            this.outputDir = v;
            return this;
        }

        public Builder stubsOut(Path v) {
            this.stubsOut = v;
            return this;
        }

        public Builder jvmTarget(int v) {
            this.jvmTarget = v;
            return this;
        }

        public Builder workerClasspath(List<Path> v) {
            this.workerClasspath = v;
            return this;
        }

        public Builder workDir(Path v) {
            this.workDir = v;
            return this;
        }

        public Builder extraArgs(List<String> v) {
            this.extraArgs = v;
            return this;
        }

        public GroovycRequest build() {
            return new GroovycRequest(
                    sources,
                    javaSourceRoots,
                    classpath,
                    processorPath,
                    outputDir,
                    stubsOut,
                    jvmTarget,
                    workerClasspath,
                    workDir,
                    extraArgs);
        }
    }
}
