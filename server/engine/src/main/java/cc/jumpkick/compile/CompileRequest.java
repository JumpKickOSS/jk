// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.jdk.SupportedJdk;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import lombok.Builder;

/**
 * Input to {@link JavacRunner#compile(CompileRequest)}: sources, classpath, release, and optional
 * {@code .class} output. A null {@link #outputDir()} is check-only ({@code jk check}).
 */
@Builder
public record CompileRequest(
        List<Path> sources,
        List<Path> classpath,
        Path outputDir,
        int release,
        List<String> extraOptions,
        Path javaHome,
        List<Path> processorPath,
        String scalaVersion,
        List<Path> compilerClasspath,
        Path scalaLibraryJar,
        Path scalaCompilerJar,
        Path scalaBridgeJar) {

    public CompileRequest {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(extraOptions, "extraOptions");
        Objects.requireNonNull(processorPath, "processorPath");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        extraOptions = List.copyOf(extraOptions);
        processorPath = List.copyOf(processorPath);
        if (compilerClasspath == null) compilerClasspath = List.of();
        compilerClasspath = List.copyOf(compilerClasspath);
        if (release < SupportedJdk.MIN_MAJOR) {
            throw new IllegalArgumentException("release must be >= " + SupportedJdk.MIN_MAJOR + ", got: " + release);
        }
    }

    public static class CompileRequestBuilder {
        private List<Path> sources = List.of();
        private List<Path> classpath = List.of();
        private int release = 25;
        private List<String> extraOptions = List.of();
        private List<Path> processorPath = List.of();
        private List<Path> compilerClasspath = List.of();
    }

    /** Java-only compile (no Scala compiler identity). */
    public CompileRequest(
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            int release,
            List<String> extraOptions,
            Path javaHome,
            List<Path> processorPath) {
        this(
                sources,
                classpath,
                outputDir,
                release,
                extraOptions,
                javaHome,
                processorPath,
                null,
                List.of(),
                null,
                null,
                null);
    }

    /** True when this request is a mixed Java+Scala Zinc session. */
    public boolean mixedScala() {
        return scalaVersion != null && !scalaVersion.isBlank() && !compilerClasspath.isEmpty();
    }

    public boolean isCheckOnly() {
        return outputDir == null;
    }
}
