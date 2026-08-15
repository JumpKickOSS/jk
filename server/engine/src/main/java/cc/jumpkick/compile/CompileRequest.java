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
        List<Path> processorPath) {

    public CompileRequest {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(extraOptions, "extraOptions");
        Objects.requireNonNull(processorPath, "processorPath");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        extraOptions = List.copyOf(extraOptions);
        processorPath = List.copyOf(processorPath);
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
    }

    public boolean isCheckOnly() {
        return outputDir == null;
    }
}
