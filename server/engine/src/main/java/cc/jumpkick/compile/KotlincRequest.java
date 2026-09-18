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
 * Input to {@link WorkerCompileDriver}'s Kotlin arm (forks {@code jk-kotlin-compiler} / Build
 * Tools API).
 * {@code workerClasspath} is the plugin jar + BTA closure; a null {@code workingDir} is a full
 * compile. Compiler plugins must use {@link Plugin}, not raw {@code -Xplugin} in
 * {@code extraArgs} (BTA ignores those).
 */
@Builder
public record KotlincRequest(
        List<Path> sources,
        List<Path> classpath,
        Path outputDir,
        int jvmTarget,
        List<Path> workerClasspath,
        Path javaHome,
        @Nullable Path workingDir,
        @Nullable Path snapshotDir,
        List<String> extraArgs,
        List<Plugin> plugins,
        /**
         * {@code -module-name}, or null for the default. Must match KSP: internal-member mangling
         * embeds it in call sites that generated Java may emit.
         */
        @Nullable String moduleName,
        /**
         * A mixed module's Java source roots — the directories {@code -Xjava-source-roots} in
         * {@code extraArgs} names, as paths. kotlinc parses the {@code .java} under them for their
         * declarations and links against them, so they are compile inputs: the action key hashes
         * each file's declaration digest ({@link KotlincInputs#javaSources}). Empty for a
         * Kotlin-only module.
         */
        List<Path> javaSourceRoots) {

    /** One compiler plugin: id, jar, and {@code key=value} options. */
    public record Plugin(String id, Path jar, List<String> options) {

        public Plugin {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(jar, "jar");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public KotlincRequest {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(workerClasspath, "workerClasspath");
        Objects.requireNonNull(javaHome, "javaHome");
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        workerClasspath = List.copyOf(workerClasspath);
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        javaSourceRoots = javaSourceRoots == null ? List.of() : List.copyOf(javaSourceRoots);
        if (jvmTarget < JavaRelease.OLDEST) {
            throw new IllegalArgumentException("jvmTarget must be >= " + JavaRelease.OLDEST + ", got: " + jvmTarget);
        }
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("workerClasspath must include the worker jar + BTA closure");
        }
    }

    /**
     * Lombok fills the staged fields in; the class is declared here only to give the collection and
     * scalar defaults. Unmarked because those generated fields are write-once builder state, not the
     * record's contract.
     */
    @NullUnmarked
    public static class KotlincRequestBuilder {
        private List<Path> sources = List.of();
        private List<Path> classpath = List.of();
        private int jvmTarget = 21;
        private List<Path> workerClasspath = List.of();
        private List<String> extraArgs = List.of();
        private List<Plugin> plugins = List.of();
        private List<Path> javaSourceRoots = List.of();
    }

    public boolean incremental() {
        return workingDir != null;
    }
}
