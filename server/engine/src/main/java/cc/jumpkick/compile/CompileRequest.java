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
 * Input to Java / mixed Java+Scala compile: sources, classpath, release, and optional {@code .class}
 * output. A null {@link #outputDir()} is check-only ({@code jk check}).
 */
@Builder
public record CompileRequest(
        List<Path> sources,
        List<Path> classpath,
        @Nullable Path outputDir,
        int release,
        List<String> extraOptions,
        @Nullable Path javaHome,
        List<Path> processorPath,
        @Nullable String scalaVersion,
        List<Path> compilerClasspath,
        @Nullable Path scalaLibraryJar,
        @Nullable Path scalaCompilerJar,
        @Nullable Path scalaBridgeJar) {

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
        if (release < JavaRelease.OLDEST) {
            throw new IllegalArgumentException("release must be >= " + JavaRelease.OLDEST + ", got: " + release);
        }
    }

    /**
     * Lombok fills the staged fields in; the class is declared here only to give the collection and
     * scalar defaults. Unmarked because those generated fields are write-once builder state, not the
     * record's contract.
     */
    @NullUnmarked
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
            @Nullable Path outputDir,
            int release,
            List<String> extraOptions,
            @Nullable Path javaHome,
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
