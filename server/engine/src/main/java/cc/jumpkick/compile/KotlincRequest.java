// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Input to {@link KotlincDriver} (forks {@code jk-kotlin-compiler} / Build Tools API).
 * {@code workerClasspath} is the plugin jar + BTA closure; {@code workingDir} null means full
 * (non-incremental) compile. Compiler plugins must use {@link Plugin}, not raw {@code -Xplugin}
 * in {@code extraArgs} (BTA ignores those).
 */
public record KotlincRequest(
        List<Path> sources,
        List<Path> classpath,
        Path outputDir,
        int jvmTarget,
        List<Path> workerClasspath,
        Path javaHome,
        Path workingDir,
        Path snapshotDir,
        List<String> extraArgs,
        List<Plugin> plugins,
        /**
         * {@code -module-name}, or null for the default. Must match KSP: internal-member mangling
         * embeds it in call sites that generated Java may emit.
         */
        String moduleName) {

    /** One compiler plugin: its id, jar, and {@code key=value} options. */
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
        if (jvmTarget < 8) {
            throw new IllegalArgumentException("jvmTarget must be >= 8, got: " + jvmTarget);
        }
        if (workerClasspath.isEmpty()) {
            throw new IllegalArgumentException("workerClasspath must include the worker jar + BTA closure");
        }
    }

    /** Back-compat constructor: no module name. */
    public KotlincRequest(
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            int jvmTarget,
            List<Path> workerClasspath,
            Path javaHome,
            Path workingDir,
            Path snapshotDir,
            List<String> extraArgs,
            List<Plugin> plugins) {
        this(
                sources,
                classpath,
                outputDir,
                jvmTarget,
                workerClasspath,
                javaHome,
                workingDir,
                snapshotDir,
                extraArgs,
                plugins,
                null);
    }

    /** Back-compat constructor: no compiler plugins. */
    public KotlincRequest(
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            int jvmTarget,
            List<Path> workerClasspath,
            Path javaHome,
            Path workingDir,
            Path snapshotDir,
            List<String> extraArgs) {
        this(
                sources,
                classpath,
                outputDir,
                jvmTarget,
                workerClasspath,
                javaHome,
                workingDir,
                snapshotDir,
                extraArgs,
                List.of(),
                null);
    }

    public boolean incremental() {
        return workingDir != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<Path> sources = List.of();
        private List<Path> classpath = List.of();
        private Path outputDir;
        private int jvmTarget = 21;
        private List<Path> workerClasspath = List.of();
        private Path javaHome;
        private Path workingDir;
        private Path snapshotDir;
        private List<String> extraArgs = List.of();
        private List<Plugin> plugins = List.of();
        private String moduleName;

        public Builder sources(List<Path> v) {
            this.sources = v;
            return this;
        }

        public Builder classpath(List<Path> v) {
            this.classpath = v;
            return this;
        }

        public Builder outputDir(Path v) {
            this.outputDir = v;
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

        public Builder javaHome(Path v) {
            this.javaHome = v;
            return this;
        }

        public Builder workingDir(Path v) {
            this.workingDir = v;
            return this;
        }

        public Builder snapshotDir(Path v) {
            this.snapshotDir = v;
            return this;
        }

        public Builder extraArgs(List<String> v) {
            this.extraArgs = v;
            return this;
        }

        public Builder plugins(List<Plugin> v) {
            this.plugins = v;
            return this;
        }

        public Builder moduleName(String v) {
            this.moduleName = v;
            return this;
        }

        public KotlincRequest build() {
            return new KotlincRequest(
                    sources,
                    classpath,
                    outputDir,
                    jvmTarget,
                    workerClasspath,
                    javaHome,
                    workingDir,
                    snapshotDir,
                    extraArgs,
                    plugins,
                    moduleName);
        }
    }
}
